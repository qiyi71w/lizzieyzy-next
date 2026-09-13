#requires -Version 7.0

[CmdletBinding()]
param(
    [string]$Executable = 'python',
    [string[]]$CommandArguments = @(
        'scripts/run_local_ci.py'
        '--profile'
        'windows'
        '--group'
        'java'
        '--require-clean'
        '--summary-dir'
        'target/local-ci/windows-java'
    ),
    [string]$WorkingDirectory = (Split-Path -Parent $PSScriptRoot),
    [string]$OutputDirectory = 'target/ci-diagnostics',
    [ValidateRange(1, [int]::MaxValue)]
    [int]$TimeoutSeconds = 1380,
    [ValidateRange(1, [int]::MaxValue)]
    [int]$StallSeconds = 120,
    [ValidateRange(1, [int]::MaxValue)]
    [int]$DumpIntervalSeconds = 30,
    [ValidateRange(1, [int]::MaxValue)]
    [int]$DumpTimeoutSeconds = 10
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'


$Utf8NoBom = [System.Text.UTF8Encoding]::new($false)
$WorkingDirectory = (Resolve-Path -LiteralPath $WorkingDirectory).Path
if (-not [System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path -Path $WorkingDirectory -ChildPath $OutputDirectory
}
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)

if (Test-Path -LiteralPath $OutputDirectory) {
    if (-not (Test-Path -LiteralPath $OutputDirectory -PathType Container)) {
        throw "Diagnostics output is not a directory: $OutputDirectory"
    }
    if ([System.IO.Directory]::EnumerateFileSystemEntries($OutputDirectory).GetEnumerator().MoveNext()) {
        throw "Refusing to mix diagnostics with existing evidence: $OutputDirectory"
    }
}
else {
    [System.IO.Directory]::CreateDirectory($OutputDirectory) | Out-Null
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory)] [string]$Path,
        [Parameter(Mandatory)] [object]$Value
    )

    $json = $Value | ConvertTo-Json -Depth 12
    [System.IO.File]::WriteAllText($Path, $json + [Environment]::NewLine, $script:Utf8NoBom)
}

function Get-ProcessCreationTicks {
    param([Parameter(Mandatory)] [object]$Process)

    return ([datetime]$Process.CreationDate).ToUniversalTime().Ticks
}

function Get-ProcessIdentityKey {
    param([Parameter(Mandatory)] [object]$Process)

    $ticks = Get-ProcessCreationTicks -Process $Process
    return "$($Process.ProcessId):$ticks"
}

function Get-JobProcessIds {
    if ($script:JobHandle -eq [IntPtr]::Zero) { return @() }
    return @([WindowsCiJob]::GetProcessIds($script:JobHandle))
}

function Update-OwnedProcesses {
    $jobPids = @(Get-JobProcessIds)
    if ($jobPids.Count -eq 0) { return @() }

    $pidSet = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($processId in $jobPids) { $pidSet.Add([int]$processId) | Out-Null }

    $owned = [System.Collections.Generic.List[object]]::new()
    foreach ($item in @(Get-CimInstance -ClassName Win32_Process -ErrorAction Stop)) {
        if (-not $item.CreationDate -or -not $pidSet.Contains([int]$item.ProcessId)) { continue }
        $key = Get-ProcessIdentityKey -Process $item
        $script:OwnedProcesses[$key] = [pscustomobject]@{
            ProcessId = [int]$item.ProcessId
            CreationTicks = Get-ProcessCreationTicks -Process $item
        }
        $owned.Add([pscustomobject]@{ Key = $key; Process = $item })
    }
    return @($owned)
}

function Test-OwnedIdentity {
    param(
        [Parameter(Mandatory)] [int]$ProcessId,
        [Parameter(Mandatory)] [long]$CreationTicks
    )

    $candidate = Get-CimInstance -ClassName Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction SilentlyContinue
    if (-not $candidate -or -not $candidate.CreationDate) { return $false }
    if ((Get-ProcessCreationTicks -Process $candidate) -ne $CreationTicks) { return $false }

    $nativeProcess = $null
    try {
        $nativeProcess = [System.Diagnostics.Process]::GetProcessById($ProcessId)
        return [WindowsCiJob]::ContainsProcess($script:JobHandle, $nativeProcess.Handle)
    }
    catch {
        return $false
    }
    finally {
        if ($nativeProcess) { $nativeProcess.Dispose() }
    }
}

function Stop-OwnedTree {
    param([int]$BudgetSeconds = 8)

    $before = @(Get-JobProcessIds)
    $failed = @()
    if ($script:JobHandle -eq [IntPtr]::Zero -or $before.Count -eq 0) {
        return [pscustomobject]@{ Stopped = @(); Failed = @() }
    }

    try {
        [WindowsCiJob]::Terminate($script:JobHandle, 125)
    }
    catch {
        $failed += [ordered]@{ pid = $null; reason = $_.Exception.Message }
    }

    $deadline = [datetime]::UtcNow.AddSeconds($BudgetSeconds)
    do {
        $remaining = @(Get-JobProcessIds)
        if ($remaining.Count -eq 0) { break }
        Start-Sleep -Milliseconds 100
    } while ([datetime]::UtcNow -lt $deadline)

    foreach ($processId in $remaining) {
        $failed += [ordered]@{ pid = $processId; reason = 'job termination timed out' }
    }
    return [pscustomobject]@{ Stopped = @($before | Where-Object { $remaining -notcontains $_ }); Failed = @($failed) }
}

function Convert-ProcessSnapshot {
    param([Parameter(Mandatory)] [object]$Entry)

    $process = $Entry.Process
    $kernel = if ($null -eq $process.KernelModeTime) { 0L } else { [long]$process.KernelModeTime }
    $user = if ($null -eq $process.UserModeTime) { 0L } else { [long]$process.UserModeTime }
    return [ordered]@{
        pid = [int]$process.ProcessId
        parentPid = [int]$process.ParentProcessId
        creationTimeUtc = ([datetime]$process.CreationDate).ToUniversalTime().ToString('o')
        name = $process.Name
        executablePath = $process.ExecutablePath
        kernelTime100ns = $kernel
        userTime100ns = $user
        cpuSeconds = ($kernel + $user) / 10000000.0
        workingSetBytes = [long]$process.WorkingSetSize
        threadCount = [int]$process.ThreadCount
        handleCount = [int]$process.HandleCount
    }
}

function Test-JavaProcess {
    param([Parameter(Mandatory)] [object]$Process)

    return $Process.Name -in @('java.exe', 'javaw.exe', 'java', 'javaw')
}

function Invoke-JcmdDumps {
    param(
        [Parameter(Mandatory)] [object[]]$Owned,
        [Parameter(Mandatory)] [int]$Episode,
        [Parameter(Mandatory)] [int]$Ordinal,
        [Parameter(Mandatory)] [datetime]$HardDeadlineUtc
    )

    $results = [System.Collections.Generic.List[object]]::new()
    $jcmd = if ($env:JAVA_HOME) { Join-Path -Path $env:JAVA_HOME -ChildPath 'bin\jcmd.exe' } else { $null }
    $javaEntries = @($Owned | Where-Object { Test-JavaProcess -Process $_.Process })
    if ($javaEntries.Count -eq 0) {
        return @()
    }


    if (-not $jcmd -or -not (Test-Path -LiteralPath $jcmd -PathType Leaf)) {
        $results.Add([ordered]@{
            status = 'unavailable'
            reason = 'JAVA_HOME/bin/jcmd.exe was not found'
            javaHome = $env:JAVA_HOME
        })
        return @($results)
    }

    $running = [System.Collections.Generic.List[object]]::new()
    foreach ($entry in $javaEntries) {
        $identity = $script:OwnedProcesses[$entry.Key]
        if (-not (Test-OwnedIdentity -ProcessId $identity.ProcessId -CreationTicks $identity.CreationTicks)) {
            $results.Add([ordered]@{ pid = $identity.ProcessId; status = 'exited_before_dump' })
            continue
        }

        $threadPath = Join-Path -Path $OutputDirectory -ChildPath "threads-$Episode-$Ordinal-$($identity.ProcessId).txt"
        $stdoutPath = "$threadPath.tmp"
        $stderrPath = "$threadPath.stderr.tmp"
        $stdoutFile = $null
        $stderrFile = $null
        try {
            $stdoutFile = [System.IO.FileStream]::new($stdoutPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read, 65536, $true)
            $stderrFile = [System.IO.FileStream]::new($stderrPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read, 65536, $true)
            $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
            $startInfo.FileName = $jcmd
            $startInfo.UseShellExecute = $false
            $startInfo.CreateNoWindow = $true
            $startInfo.RedirectStandardOutput = $true
            $startInfo.RedirectStandardError = $true
            $startInfo.ArgumentList.Add([string]$identity.ProcessId)
            $startInfo.ArgumentList.Add('Thread.print')
            $startInfo.ArgumentList.Add('-l')
            $dumpProcess = [System.Diagnostics.Process]::Start($startInfo)
            $stdoutCopy = $dumpProcess.StandardOutput.BaseStream.CopyToAsync($stdoutFile)
            $stderrCopy = $dumpProcess.StandardError.BaseStream.CopyToAsync($stderrFile)
            $running.Add([pscustomobject]@{
                TargetPid = $identity.ProcessId
                Process = $dumpProcess
                ThreadPath = $threadPath
                StdoutPath = $stdoutPath
                StderrPath = $stderrPath
                StdoutFile = $stdoutFile
                StderrFile = $stderrFile
                StdoutCopy = $stdoutCopy
                StderrCopy = $stderrCopy
                StartedUtc = [datetime]::UtcNow
            })
        }
        catch {
            if ($stdoutFile) { $stdoutFile.Dispose() }
            if ($stderrFile) { $stderrFile.Dispose() }
            Remove-Item -LiteralPath $stdoutPath, $stderrPath -Force -ErrorAction SilentlyContinue
            $results.Add([ordered]@{ pid = $identity.ProcessId; status = 'launch_failed'; reason = $_.Exception.Message })
        }
    }

    foreach ($dump in $running) {
        $perDumpDeadline = $dump.StartedUtc.AddSeconds($DumpTimeoutSeconds)
        while (-not $dump.Process.HasExited -and [datetime]::UtcNow -lt $perDumpDeadline -and [datetime]::UtcNow -lt $HardDeadlineUtc) {
            Start-Sleep -Milliseconds 100
        }
        $timedOut = -not $dump.Process.HasExited
        if ($timedOut) {
            try { $dump.Process.Kill($true) } catch { }
        }
        if ($dump.Process.HasExited) {
            $dump.Process.WaitForExit()
        }
        $remainingWaitMs = [math]::Max(0, [math]::Min(1000, [int]($HardDeadlineUtc - [datetime]::UtcNow).TotalMilliseconds))
        try { $dump.StdoutCopy.Wait($remainingWaitMs) | Out-Null } catch { }
        try { $dump.StderrCopy.Wait($remainingWaitMs) | Out-Null } catch { }
        $dump.StdoutFile.Dispose()
        $dump.StderrFile.Dispose()

        $stderrText = try { [System.IO.File]::ReadAllText($dump.StderrPath, $Utf8NoBom) } catch { '' }
        $stdoutText = try { [System.IO.File]::ReadAllText($dump.StdoutPath, $Utf8NoBom) } catch { '' }
        if (-not $timedOut -and $dump.Process.ExitCode -eq 0) {
            Move-Item -LiteralPath $dump.StdoutPath -Destination $dump.ThreadPath -ErrorAction Stop
            $results.Add([ordered]@{
                pid = $dump.TargetPid
                status = 'completed'
                exitCode = 0
                threadFile = [System.IO.Path]::GetFileName($dump.ThreadPath)
            })
        }
        else {
            Remove-Item -LiteralPath $dump.StdoutPath -Force -ErrorAction SilentlyContinue
            $results.Add([ordered]@{
                pid = $dump.TargetPid
                status = if ($timedOut) { 'timed_out' } else { 'failed' }
                exitCode = if ($timedOut) { $null } else { $dump.Process.ExitCode }
                reason = if ($timedOut) {
                    "jcmd exceeded ${DumpTimeoutSeconds}s"
                }
                else {
                    $detail = ($stderrText + [Environment]::NewLine + $stdoutText).Trim()
                    if ($detail) { $detail } else { "jcmd exited with code $($dump.Process.ExitCode)" }
                }
            })
        }
        Remove-Item -LiteralPath $dump.StderrPath -Force -ErrorAction SilentlyContinue
        $dump.Process.Dispose()
    }

    return @($results)
}

function Save-DiagnosticSnapshot {
    param(
        [Parameter(Mandatory)] [string]$Reason,
        [Parameter(Mandatory)] [int]$Episode,
        [Parameter(Mandatory)] [int]$Ordinal,
        [Parameter(Mandatory)] [datetime]$HardDeadlineUtc
    )

    $capturedUtc = [datetime]::UtcNow
    $processes = @()
    $jcmdResults = @()
    $captureErrors = [System.Collections.Generic.List[string]]::new()
    try {
        $owned = Update-OwnedProcesses
        $processes = @($owned | ForEach-Object { Convert-ProcessSnapshot -Entry $_ })
        try {
            $jcmdResults = @(Invoke-JcmdDumps -Owned $owned -Episode $Episode -Ordinal $Ordinal -HardDeadlineUtc $HardDeadlineUtc)
        }
        catch {
            $captureErrors.Add("jcmd capture: $($_.Exception.Message)")
        }
    }
    catch {
        $captureErrors.Add("process capture: $($_.Exception.Message)")
    }

    $snapshotPath = Join-Path -Path $OutputDirectory -ChildPath "snapshot-$Episode-$Ordinal.json"
    Write-JsonFile -Path $snapshotPath -Value ([ordered]@{
        capturedUtc = $capturedUtc.ToString('o')
        reason = $Reason
        episode = $Episode
        ordinal = $Ordinal
        processes = $processes
        jcmd = $jcmdResults
        captureErrors = @($captureErrors)
    })
}

function Save-DiagnosticPair {
    param(
        [Parameter(Mandatory)] [string]$Reason,
        [Parameter(Mandatory)] [int]$Episode,
        [Parameter(Mandatory)] [datetime]$HardDeadlineUtc
    )

    Save-DiagnosticSnapshot -Reason $Reason -Episode $Episode -Ordinal 1 -HardDeadlineUtc $HardDeadlineUtc

    $secondAt = [datetime]::UtcNow.AddSeconds($DumpIntervalSeconds)
    while ([datetime]::UtcNow -lt $secondAt -and [datetime]::UtcNow -lt $HardDeadlineUtc) {
        Start-Sleep -Milliseconds 200
    }
    if ([datetime]::UtcNow -lt $HardDeadlineUtc) {
        Save-DiagnosticSnapshot -Reason $Reason -Episode $Episode -Ordinal 2 -HardDeadlineUtc $HardDeadlineUtc
    }
    else {
        Write-JsonFile -Path (Join-Path -Path $OutputDirectory -ChildPath "snapshot-$Episode-2.json") -Value ([ordered]@{
            capturedUtc = [datetime]::UtcNow.ToString('o')
            reason = $Reason
            episode = $Episode
            ordinal = 2
            processes = @()
            jcmd = @()
            captureErrors = @('hard deadline reached before second snapshot')
        })
    }
}

function Read-JunitProgress {
    param([hashtable]$KnownFiles)

    $changed = $false
    $lastEvent = $null
    foreach ($file in @(Get-ChildItem -LiteralPath $OutputDirectory -Filter 'junit-*.jsonl' -File -ErrorAction SilentlyContinue)) {
        $key = $file.FullName
        $previous = $KnownFiles[$key]
        if (-not $previous) {
            $previous = [pscustomobject]@{
                Offset = 0L
                PlanDepth = 0
                PendingBytes = [byte[]]::new(0)
            }
            $KnownFiles[$key] = $previous
        }

        $stream = $null
        try {
            # Windows can leave FileInfo.Length at zero while Java holds the
            # append stream open. The opened stream length is authoritative.
            $stream = [System.IO.FileStream]::new(
                $key,
                [System.IO.FileMode]::Open,
                [System.IO.FileAccess]::Read,
                [System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete
            )
            if ($stream.Length -lt $previous.Offset) {
                $previous.Offset = 0L
                $previous.PlanDepth = 0
                $previous.PendingBytes = [byte[]]::new(0)
            }
            if ($stream.Length -eq $previous.Offset) { continue }

            $changed = $true
            $newPlanDepth = $previous.PlanDepth
            $stream.Position = $previous.Offset
            $remaining = $stream.Length - $previous.Offset
            if ($remaining -gt [int]::MaxValue) {
                throw "JUnit progress increment is too large to inspect safely: $remaining bytes"
            }
            $newBytes = [byte[]]::new([int]$remaining)
            $read = 0
            while ($read -lt $newBytes.Length) {
                $count = $stream.Read($newBytes, $read, $newBytes.Length - $read)
                if ($count -eq 0) { break }
                $read += $count
            }
            if ($read -ne $newBytes.Length) {
                $actualBytes = [byte[]]::new($read)
                [array]::Copy($newBytes, 0, $actualBytes, 0, $read)
                $newBytes = $actualBytes
            }

            $combined = [byte[]]::new($previous.PendingBytes.Length + $newBytes.Length)
            [array]::Copy($previous.PendingBytes, 0, $combined, 0, $previous.PendingBytes.Length)
            [array]::Copy($newBytes, 0, $combined, $previous.PendingBytes.Length, $newBytes.Length)
            $lastNewline = -1
            for ($index = $combined.Length - 1; $index -ge 0; $index--) {
                if ($combined[$index] -eq 10) {
                    $lastNewline = $index
                    break
                }
            }

            if ($lastNewline -ge 0) {
                $text = $Utf8NoBom.GetString($combined, 0, $lastNewline + 1)
                foreach ($line in ($text -split "`n")) {
                    $line = $line.TrimEnd("`r")
                    if (-not $line) { continue }
                    try { $event = $line | ConvertFrom-Json -ErrorAction Stop } catch { continue }
                    if ($event.event -eq 'PLAN_STARTED') { $newPlanDepth++ }
                    if ($event.event -eq 'PLAN_FINISHED') { $newPlanDepth = [math]::Max(0, $newPlanDepth - 1) }
                    $lastEvent = $event
                }
                $pendingLength = $combined.Length - $lastNewline - 1
                $newPending = [byte[]]::new($pendingLength)
                if ($pendingLength -gt 0) {
                    [array]::Copy($combined, $lastNewline + 1, $newPending, 0, $pendingLength)
                }
            }
            else {
                $newPending = $combined
            }
            $previous.PlanDepth = $newPlanDepth
            $previous.PendingBytes = $newPending
            $previous.Offset = $stream.Position
        }
        catch {
            # Preserve the last complete offset/depth and retry on the next poll.
        }
        finally {
            if ($stream) { $stream.Dispose() }
        }
    }

    return [pscustomobject]@{
        Changed = $changed
        ActivePlan = @($KnownFiles.Values | Where-Object { $_.PlanDepth -gt 0 }).Count -gt 0
        LastEvent = $lastEvent
    }
}

if (-not ('WindowsCiJob' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Threading;

public static class WindowsCiJob {
    private const uint JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_BASIC_LIMIT_INFORMATION {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IO_COUNTERS {
        public ulong ReadOperationCount;
        public ulong WriteOperationCount;
        public ulong OtherOperationCount;
        public ulong ReadTransferCount;
        public ulong WriteTransferCount;
        public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JOBOBJECT_EXTENDED_LIMIT_INFORMATION {
        public JOBOBJECT_BASIC_LIMIT_INFORMATION BasicLimitInformation;
        public IO_COUNTERS IoInfo;
        public UIntPtr ProcessMemoryLimit;
        public UIntPtr JobMemoryLimit;
        public UIntPtr PeakProcessMemoryUsed;
        public UIntPtr PeakJobMemoryUsed;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateJobObject(IntPtr securityAttributes, string name);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetInformationJobObject(IntPtr job, int infoClass, IntPtr info, uint length);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool QueryInformationJobObject(IntPtr job, int infoClass, IntPtr info, uint length, out uint returnLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool result);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool TerminateJobObject(IntPtr job, uint exitCode);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);

    public static IntPtr CreateKillOnClose() {
        IntPtr job = CreateJobObject(IntPtr.Zero, null);
        if (job == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
        var limits = new JOBOBJECT_EXTENDED_LIMIT_INFORMATION();
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        int size = Marshal.SizeOf<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>();
        IntPtr buffer = Marshal.AllocHGlobal(size);
        try {
            Marshal.StructureToPtr(limits, buffer, false);
            if (!SetInformationJobObject(job, 9, buffer, (uint)size)) {
                int error = Marshal.GetLastWin32Error();
                CloseHandle(job);
                throw new Win32Exception(error);
            }
            return job;
        } finally {
            Marshal.FreeHGlobal(buffer);
        }
    }

    public static void Assign(IntPtr job, IntPtr process) {
        if (!AssignProcessToJobObject(job, process)) throw new Win32Exception(Marshal.GetLastWin32Error());
    }

    public static int[] GetProcessIds(IntPtr job) {
        const int size = 65536;
        IntPtr buffer = Marshal.AllocHGlobal(size);
        try {
            uint returned;
            if (!QueryInformationJobObject(job, 3, buffer, size, out returned)) {
                throw new Win32Exception(Marshal.GetLastWin32Error());
            }
            int count = Marshal.ReadInt32(buffer, 4);
            int capacity = (size - 8) / IntPtr.Size;
            if (count < 0 || count > capacity) throw new InvalidOperationException("Invalid job process count.");
            var result = new int[count];
            for (int i = 0; i < count; i++) {
                long value = IntPtr.Size == 8
                    ? Marshal.ReadInt64(buffer, 8 + i * IntPtr.Size)
                    : Marshal.ReadInt32(buffer, 8 + i * IntPtr.Size);
                result[i] = checked((int)value);
            }
            return result;
        } finally {
            Marshal.FreeHGlobal(buffer);
        }
    }

    public static bool ContainsProcess(IntPtr job, IntPtr process) {
        bool result;
        if (!IsProcessInJob(process, job, out result)) throw new Win32Exception(Marshal.GetLastWin32Error());
        return result;
    }

    public static void Terminate(IntPtr job, uint exitCode) {
        if (!TerminateJobObject(job, exitCode)) throw new Win32Exception(Marshal.GetLastWin32Error());
    }

    public static void Close(IntPtr job) {
        if (job != IntPtr.Zero && !CloseHandle(job)) throw new Win32Exception(Marshal.GetLastWin32Error());
    }
}

public sealed class WindowsCiChild : IDisposable {
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct STARTUPINFO {
        public int Size;
        public string Reserved;
        public string Desktop;
        public string Title;
        public uint X, Y, XSize, YSize, XCountChars, YCountChars, FillAttribute, Flags;
        public ushort ShowWindow, ReservedBytes;
        public IntPtr ReservedPointer, StandardInput, StandardOutput, StandardError;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_INFORMATION {
        public IntPtr Process, Thread;
        public uint ProcessId, ThreadId;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CreateProcessW(string application, System.Text.StringBuilder command,
        IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, uint flags,
        IntPtr environment, string directory, ref STARTUPINFO startup, out PROCESS_INFORMATION process);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint ResumeThread(IntPtr thread);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool TerminateProcess(IntPtr process, uint exitCode);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);

    public System.Diagnostics.Process Process { get; private set; }
    public System.IO.Pipes.AnonymousPipeServerStream StandardOutput { get; private set; }
    public System.IO.Pipes.AnonymousPipeServerStream StandardError { get; private set; }

    private static string Quote(string argument) {
        var result = new System.Text.StringBuilder("\"");
        int slashes = 0;
        foreach (char value in argument) {
            if (value == '\\') { slashes++; continue; }
            result.Append('\\', value == '"' ? slashes * 2 + 1 : slashes);
            result.Append(value);
            slashes = 0;
        }
        return result.Append('\\', slashes * 2).Append('"').ToString();
    }

    public static WindowsCiChild Start(IntPtr job, System.Diagnostics.ProcessStartInfo info) {
        var child = new WindowsCiChild();
        var native = new PROCESS_INFORMATION();
        IntPtr environment = IntPtr.Zero;
        try {
            child.StandardOutput = new System.IO.Pipes.AnonymousPipeServerStream(
                System.IO.Pipes.PipeDirection.In, System.IO.HandleInheritability.Inheritable);
            child.StandardError = new System.IO.Pipes.AnonymousPipeServerStream(
                System.IO.Pipes.PipeDirection.In, System.IO.HandleInheritability.Inheritable);
            using (var input = new System.IO.Pipes.AnonymousPipeServerStream(
                System.IO.Pipes.PipeDirection.Out, System.IO.HandleInheritability.Inheritable)) {
                var startup = new STARTUPINFO {
                    Size = Marshal.SizeOf<STARTUPINFO>(),
                    Flags = 0x00000100,
                    StandardInput = input.ClientSafePipeHandle.DangerousGetHandle(),
                    StandardOutput = child.StandardOutput.ClientSafePipeHandle.DangerousGetHandle(),
                    StandardError = child.StandardError.ClientSafePipeHandle.DangerousGetHandle()
                };
                var command = new System.Text.StringBuilder(Quote(info.FileName));
                foreach (string argument in info.ArgumentList) command.Append(' ').Append(Quote(argument));
                var variables = new System.Collections.Generic.List<string>();
                foreach (var entry in info.Environment) variables.Add(entry.Key + "=" + entry.Value);
                variables.Sort(StringComparer.OrdinalIgnoreCase);
                environment = Marshal.StringToHGlobalUni(string.Join("\0", variables) + "\0\0");

                // Assign the real target before it can run or create descendants.
                // A packaged PowerShell bootstrap can break children out of its job.
                if (!CreateProcessW(info.FileName, command, IntPtr.Zero, IntPtr.Zero, true,
                    0x08000404, environment, info.WorkingDirectory, ref startup, out native)) {
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }
                input.DisposeLocalCopyOfClientHandle();
                child.StandardOutput.DisposeLocalCopyOfClientHandle();
                child.StandardError.DisposeLocalCopyOfClientHandle();
                WindowsCiJob.Assign(job, native.Process);
                child.Process = System.Diagnostics.Process.GetProcessById(checked((int)native.ProcessId));
                // Retain an identity-bound handle even if the command exits immediately after resume.
                IntPtr retainedHandle = child.Process.Handle;
                if (ResumeThread(native.Thread) == uint.MaxValue) {
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }
            }
            return child;
        } catch {
            if (native.Process != IntPtr.Zero) TerminateProcess(native.Process, 125);
            child.Dispose();
            if (child.Process != null) child.Process.Dispose();
            throw;
        } finally {
            if (native.Thread != IntPtr.Zero) CloseHandle(native.Thread);
            if (native.Process != IntPtr.Zero) CloseHandle(native.Process);
            if (environment != IntPtr.Zero) Marshal.FreeHGlobal(environment);
        }
    }

    public void Dispose() {
        if (StandardOutput != null) StandardOutput.Dispose();
        if (StandardError != null) StandardError.Dispose();
    }
}

public static class LizzieCiCancellation {
    private static int requested;
    public static bool Requested { get { return Volatile.Read(ref requested) != 0; } }
    public static void Install() {
        Interlocked.Exchange(ref requested, 0);
        Console.CancelKeyPress += OnCancel;
    }
    public static void Uninstall() { Console.CancelKeyPress -= OnCancel; }
    private static void OnCancel(object sender, ConsoleCancelEventArgs e) {
        e.Cancel = true;
        Interlocked.Exchange(ref requested, 1);
    }
}
'@
}

$startedUtc = [datetime]::UtcNow
$finishedUtc = $null
$status = 'monitor_error'
$supervisorExitCode = 125
$childExitCode = $null
$process = $null
$launched = $null
$stdoutFile = $null
$stderrFile = $null
$stdoutCopy = $null
$stderrCopy = $null
$cleanup = $null
$monitorError = $null
$configurationError = $false
$deadlineExpired = $false
$configuredDeadlineUnix = $env:LIZZIE_CI_DEADLINE_UNIX_SECONDS
$absoluteDeadlineUtc = $null
$deadlineCaptureUtc = $null
$script:JobHandle = [IntPtr]::Zero
$script:OwnedProcesses = @{}
$knownJunitFiles = @{}
$episode = 0
$pairCapturedThisEpisode = $false
$deadlinePairCaptured = $false
$lastProgressUtc = $startedUtc
$lastOutputLength = 0L
$lastConsoleProgressUtc = [datetime]::MinValue

try {
    [LizzieCiCancellation]::Install()
    $stdoutPath = Join-Path -Path $OutputDirectory -ChildPath 'console.stdout.log'
    $stderrPath = Join-Path -Path $OutputDirectory -ChildPath 'console.stderr.log'
    $stdoutFile = [System.IO.FileStream]::new($stdoutPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite, 65536, $true)
    $stderrFile = [System.IO.FileStream]::new($stderrPath, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite, 65536, $true)

    $absoluteDeadlineUtc = $startedUtc.AddSeconds($TimeoutSeconds)
    if ($configuredDeadlineUnix) {
        [long]$deadlineUnix = 0
        if (-not [long]::TryParse($configuredDeadlineUnix, [ref]$deadlineUnix)) {
            $configurationError = $true
            throw "LIZZIE_CI_DEADLINE_UNIX_SECONDS is not a valid integer: $configuredDeadlineUnix"
        }
        try {
            $workflowDeadlineUtc = [System.DateTimeOffset]::FromUnixTimeSeconds($deadlineUnix).UtcDateTime
        }
        catch {
            $configurationError = $true
            throw "LIZZIE_CI_DEADLINE_UNIX_SECONDS is outside the supported range: $configuredDeadlineUnix"
        }
        if ($workflowDeadlineUtc -lt $absoluteDeadlineUtc) {
            $absoluteDeadlineUtc = $workflowDeadlineUtc
        }
    }
    if ($absoluteDeadlineUtc -le [datetime]::UtcNow) {
        $deadlineExpired = $true
        throw 'The effective CI deadline has already passed; the command was not started.'
    }
    $captureReserveSeconds = $DumpIntervalSeconds + (2 * $DumpTimeoutSeconds) + 2
    $availableSeconds = [math]::Max(1, ($absoluteDeadlineUtc - $startedUtc).TotalSeconds - 1)
    $deadlineCaptureUtc = $absoluteDeadlineUtc.AddSeconds(-[math]::Min($captureReserveSeconds, $availableSeconds))

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = (Get-Command -Name $Executable -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.Environment['LIZZIE_CI_EVIDENCE_DIR'] = $OutputDirectory
    foreach ($argument in $CommandArguments) {
        $startInfo.ArgumentList.Add($argument)
    }

    $script:JobHandle = [WindowsCiJob]::CreateKillOnClose()
    Write-Host "Starting Windows CI command (timeout ${TimeoutSeconds}s, stall ${StallSeconds}s)."
    $launched = [WindowsCiChild]::Start($script:JobHandle, $startInfo)
    $process = $launched.Process
    $stdoutCopy = $launched.StandardOutput.CopyToAsync($stdoutFile)
    $stderrCopy = $launched.StandardError.CopyToAsync($stderrFile)

    while (-not $process.HasExited) {
        if ([LizzieCiCancellation]::Requested) {
            $status = 'interrupted'
            $supervisorExitCode = 130
            if (-not $pairCapturedThisEpisode -and [datetime]::UtcNow -lt $absoluteDeadlineUtc) {
                $episode++
                Save-DiagnosticPair -Reason 'interrupted' -Episode $episode -HardDeadlineUtc $absoluteDeadlineUtc
                $pairCapturedThisEpisode = $true
            }
            break
        }

        $now = [datetime]::UtcNow
        if ($now -ge $absoluteDeadlineUtc) {
            $status = 'timed_out'
            $supervisorExitCode = 124
            break
        }

        $junit = Read-JunitProgress -KnownFiles $knownJunitFiles
        $outputLength = $stdoutFile.Length + $stderrFile.Length
        $outputChanged = $outputLength -ne $lastOutputLength
        $lastOutputLength = $outputLength

        $realProgress = $junit.Changed -or ((-not $junit.ActivePlan) -and $outputChanged)
        if ($realProgress) {
            $lastProgressUtc = $now
            $pairCapturedThisEpisode = $false
        }

        if ($junit.LastEvent) {
            $eventName = [string]$junit.LastEvent.event
            $displayProperty = $junit.LastEvent.PSObject.Properties['displayName']
            if ($displayProperty -and $displayProperty.Value) {
                Write-Host ("JUnit {0}: {1}" -f $eventName, $displayProperty.Value)
            }
            else {
                Write-Host ("JUnit {0}" -f $eventName)
            }
            $lastConsoleProgressUtc = $now
        }
        elseif ($outputChanged -and ($now - $lastConsoleProgressUtc).TotalSeconds -ge 30) {
            Write-Host ("Command output continues (stdout {0} bytes, stderr {1} bytes)." -f $stdoutFile.Length, $stderrFile.Length)
            $lastConsoleProgressUtc = $now
        }

        $deadlineCaptureDue = (-not $deadlinePairCaptured) -and (-not $pairCapturedThisEpisode) -and $now -ge $deadlineCaptureUtc
        $stallCaptureDue = (-not $pairCapturedThisEpisode) -and ($now - $lastProgressUtc).TotalSeconds -ge $StallSeconds
        if ($stallCaptureDue -or $deadlineCaptureDue) {
            $episode++
            $reason = if ($deadlineCaptureDue) { 'deadline' } else { 'stall' }
            Write-Warning "Capturing $reason diagnostics (episode $episode)."
            Save-DiagnosticPair -Reason $reason -Episode $episode -HardDeadlineUtc $absoluteDeadlineUtc
            $pairCapturedThisEpisode = $true
            if ($deadlineCaptureDue) { $deadlinePairCaptured = $true }
        }

        Start-Sleep -Milliseconds 250
    }

    if ($status -in @('timed_out', 'interrupted')) {
        $cleanup = Stop-OwnedTree
    }
    else {
        $process.WaitForExit()
        $childExitCode = $process.ExitCode
        $remaining = @(Get-JobProcessIds | Where-Object { $_ -ne $process.Id })
        if (@($remaining).Count -gt 0) {
            $cleanup = Stop-OwnedTree
            $status = 'failed'
            $supervisorExitCode = if ($childExitCode -ne 0) { $childExitCode } else { 125 }
            $monitorError = 'The command exited with owned descendants still running; they were cleaned up.'
        }
        elseif ($childExitCode -eq 0) {
            $status = 'completed'
            $supervisorExitCode = 0
        }
        else {
            $status = 'failed'
            $supervisorExitCode = $childExitCode
        }
    }
}
catch {
    $monitorError = $_.Exception.ToString()
    if ($script:JobHandle -ne [IntPtr]::Zero) {
        $cleanup = Stop-OwnedTree
    }
    if ($deadlineExpired) {
        $status = 'timed_out'
        $supervisorExitCode = 124
    }
    elseif ($configurationError) {
        $status = 'monitor_error'
        $supervisorExitCode = 125
    }
    else {
        $status = if ($process) { 'monitor_error' } else { 'start_failed' }
        $supervisorExitCode = 125
    }
}
finally {
    try { [LizzieCiCancellation]::Uninstall() } catch { }

    if ($script:JobHandle -ne [IntPtr]::Zero) {
        try {
            [WindowsCiJob]::Close($script:JobHandle)
        }
        catch {
            $closeError = "Could not close the owned Windows job: $($_.Exception.Message)"
            $monitorError = if ($monitorError) { "$monitorError`n$closeError" } else { $closeError }
            if ($supervisorExitCode -eq 0) {
                $status = 'monitor_error'
                $supervisorExitCode = 125
            }
        }
        finally {
            $script:JobHandle = [IntPtr]::Zero
        }
    }

    $copyDeadline = [datetime]::UtcNow.AddSeconds(2)
    foreach ($copyTask in @($stdoutCopy, $stderrCopy)) {
        if (-not $copyTask) { continue }
        $waitMs = [math]::Max(0, [int]($copyDeadline - [datetime]::UtcNow).TotalMilliseconds)
        try { $copyTask.Wait($waitMs) | Out-Null } catch { }
    }
    if ($launched) {
        if ($stdoutCopy -and -not $stdoutCopy.IsCompleted) { try { $launched.StandardOutput.Dispose() } catch { } }
        if ($stderrCopy -and -not $stderrCopy.IsCompleted) { try { $launched.StandardError.Dispose() } catch { } }
    }
    foreach ($copyTask in @($stdoutCopy, $stderrCopy)) {
        if ($copyTask -and -not $copyTask.IsCompleted) {
            try { $copyTask.Wait(250) | Out-Null } catch { }
        }
    }
    $copyIncomplete = ($stdoutCopy -and -not $stdoutCopy.IsCompletedSuccessfully) -or ($stderrCopy -and -not $stderrCopy.IsCompletedSuccessfully)
    if ($stdoutFile) { try { $stdoutFile.Dispose() } catch { } }
    if ($stderrFile) { try { $stderrFile.Dispose() } catch { } }
    if ($copyIncomplete -and $supervisorExitCode -eq 0) {
        $status = 'monitor_error'
        $supervisorExitCode = 125
        $monitorError = 'Console drain failed or did not finish within its bounded shutdown window.'
    }
    $finishedUtc = [datetime]::UtcNow

    $javaCommand = Get-Command java.exe, java -ErrorAction SilentlyContinue | Select-Object -First 1
    $summary = [ordered]@{
        schemaVersion = 1
        status = $status
        exitCode = $supervisorExitCode
        childExitCode = $childExitCode
        startedUtc = $startedUtc.ToString('o')
        finishedUtc = $finishedUtc.ToString('o')
        durationSeconds = ($finishedUtc - $startedUtc).TotalSeconds
        pid = if ($process) { $process.Id } else { $null }
        executable = $Executable
        workingDirectory = $WorkingDirectory
        outputDirectory = $OutputDirectory
        timeoutSeconds = $TimeoutSeconds
        effectiveDeadlineUtc = if ($absoluteDeadlineUtc) { $absoluteDeadlineUtc.ToString('o') } else { $null }
        configuredDeadlineUnixSeconds = $configuredDeadlineUnix
        stallSeconds = $StallSeconds
        dumpIntervalSeconds = $DumpIntervalSeconds
        dumpTimeoutSeconds = $DumpTimeoutSeconds
        diagnosticEpisodes = $episode
        cleanup = if ($cleanup) { [ordered]@{ stoppedPids = @($cleanup.Stopped); failures = @($cleanup.Failed) } } else { $null }
        error = $monitorError
        identity = [ordered]@{
            githubSha = $env:GITHUB_SHA
            githubRunId = $env:GITHUB_RUN_ID
            githubRunAttempt = $env:GITHUB_RUN_ATTEMPT
            imageOs = $env:ImageOS
            imageVersion = $env:ImageVersion
            javaHome = $env:JAVA_HOME
            javaPath = if ($javaCommand) { $javaCommand.Source } else { $null }
        }
    }
    try {
        Write-JsonFile -Path (Join-Path -Path $OutputDirectory -ChildPath 'summary.json') -Value $summary
    }
    catch {
        Write-Error "Could not save supervisor summary: $($_.Exception.Message)" -ErrorAction Continue
        if ($supervisorExitCode -eq 0) { $supervisorExitCode = 125 }
    }
    if ($process) { $process.Dispose() }
    if ($launched) { $launched.Dispose() }
}

Write-Host "Windows CI supervisor status: $status (exit $supervisorExitCode)."
exit $supervisorExitCode
