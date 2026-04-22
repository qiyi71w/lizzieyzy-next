# ReadBoard Sync Recovery Implementation Plan

> **For agentic workers:** Choose the execution workflow explicitly. Use `superpowers:executing-plans` when higher-priority instructions prefer inline execution or tasks are tightly coupled. Use `superpowers:subagent-driven-development` only when tasks are truly independent and delegation is explicitly desired. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore deterministic Fox sync recovery on `fix-sync`, add explicit readboard force-rebuild control, keep non-Fox platforms conservative, and make analysis restart reliably after sync lands on the target board.

**Architecture:** Extend `readboard` so each frame can describe sync platform, Fox room/record-view context, and a one-shot force-rebuild flag. Consume that normalized remote context in `ReadBoard` through a narrow decision pipeline that separates transient sync state from cross-session resume state, then explicitly restart analysis after `FORCE_REBUILD`, first-frame `NO_CHANGE`, and `SINGLE_MOVE_RECOVERY`.

**Tech Stack:** Java + Maven + JUnit 5, C# + .NET 8 + xUnit verification tests, WinForms `readboard`, existing KataGo/Leelaz snapshot restore path.

---

## File Map

### `lizzieyzy-next`

- Modify: `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
  - Parse new protocol lines.
  - Split transient sync state from resume state.
  - Route Fox live/record/generic decisions through the new policy helpers.
  - Trigger explicit analysis resume after sync lands.
- Modify: `src/main/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicy.java`
  - Match only current-main-trunk ancestors.
  - Use normalized remote context instead of raw `foxMoveNumber` alone.
  - Implement strict `lastResolved + 1` matching.
- Modify: `src/main/java/featurecat/lizzie/analysis/SyncConflictTracker.java`
  - Compare normalized conflict identity instead of raw `snapshotCodes`.
- Create: `src/main/java/featurecat/lizzie/analysis/SyncRemoteContext.java`
  - Hold platform, live/record context, room token, title move info, fingerprint, fox move number, and one-shot force-rebuild flag.
- Create: `src/main/java/featurecat/lizzie/analysis/SyncResumeState.java`
  - Hold the last successful local anchor and its normalized remote identity.
- Modify: `src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java`
  - Cover ancestor-only matching, `roomToken` invalidation, record-view last-move parsing, and missing-fox fallback.
- Modify: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`
  - Cover stop/start resume, ancestor rollback, forced rebuild flag, generic conservative mode, and `start/clear` lifecycle.
- Create: `src/test/java/featurecat/lizzie/analysis/ReadBoardEngineResumeTest.java`
  - Verify rebuild and single-step recovery explicitly restart analysis.
- Modify: `src/test/java/featurecat/lizzie/analysis/LeelazLoadSgfResponseBindingTest.java`
  - Keep low-level `loadsgf` lifecycle assertions aligned with the explicit post-restore analysis hook.

### `readboard`

- Create: `/mnt/d/dev/weiqi/readboard/readboard/Core/Protocol/FoxWindowContext.cs`
  - Structured window-context DTO for live-room/record-view parsing results.
- Create: `/mnt/d/dev/weiqi/readboard/readboard/Core/Protocol/FoxWindowContextParser.cs`
  - Parse `roomToken`, live title move, record current/total move, end-of-record state, and title fingerprint.
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/Core/Models/ProtocolMessage.cs`
  - Add protocol kind for one-shot force rebuild.
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/Core/Protocol/IReadBoardProtocolAdapter.cs`
  - Declare outbound lines for `syncPlatform`, `roomToken`, record metadata, and `forceRebuild`.
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/Core/Protocol/LegacyProtocolAdapter.cs`
  - Serialize the new outbound legacy lines.
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/Core/Protocol/ISyncSessionCoordinator.cs`
  - Expose setters/senders for parsed window context and one-shot force rebuild.
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/Core/Protocol/SyncSessionCoordinator.cs`
  - Track last-sent context metadata, emit it per frame, and clear the force flag after one frame.
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/Form1.cs`
  - Resolve live/record window context from the selected window title.
  - Add a UI button or command handler that arms a one-shot force rebuild.
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/Form1.Designer.cs`
  - Add the new button to the toolbar.
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/readboard.csproj`
  - Include the new parser/context source files.
- Modify: `/mnt/d/dev/weiqi/readboard/tests/Readboard.VerificationTests/Readboard.VerificationTests.csproj`
  - Link the new parser/context production files into the verification test project.
- Create: `/mnt/d/dev/weiqi/readboard/tests/Readboard.VerificationTests/Protocol/FoxWindowContextTitleParsingTests.cs`
  - Verify live-room and record-view title parsing.
- Modify: `/mnt/d/dev/weiqi/readboard/tests/Readboard.VerificationTests/Protocol/SyncSessionCoordinatorTests.cs`
  - Verify new metadata lines and one-shot force rebuild emission.
- Modify: `/mnt/d/dev/weiqi/readboard/tests/Readboard.VerificationTests/Protocol/LegacyOutboundProtocolContractTests.cs`
  - Lock the new outbound line format.

## Task 0: Create The Safety Point And Capture Baselines

**Files:**
- Modify: none
- Test: `src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`
- Test: `/mnt/d/dev/weiqi/readboard/tests/Readboard.VerificationTests/Protocol/SyncSessionCoordinatorTests.cs`

- [ ] **Step 1: Create the backup branch from the current `fix-sync` HEAD**

```bash
git branch fix-sync-backup-20260421
git branch --list fix-sync-backup-20260421
```

Expected: one line containing `fix-sync-backup-20260421`.

- [ ] **Step 2: Run the existing Java sync tests before any code changes**

```bash
timeout 60s mvn -Dtest=SyncSnapshotRebuildPolicyTest,ReadBoardSyncDecisionTest test
```

Expected: current baseline passes or fails only on already-known `fix-sync` drift; capture the output in the working notes before changing code.

- [ ] **Step 3: Run the existing readboard protocol verification baseline**

```bash
pwsh.exe -NoLogo -Command "dotnet test D:\dev\weiqi\readboard\tests\Readboard.VerificationTests\Readboard.VerificationTests.csproj --filter FullyQualifiedName~Readboard.VerificationTests.Protocol.SyncSessionCoordinatorTests"
```

Expected: `Passed!` with the current protocol suite green.

- [ ] **Step 4: Record the two active specs at the top of the worker notes**

```text
Spec 1: /mnt/d/dev/weiqi/lizzieyzy-next/docs/SNAPSHOT_NODE_KIND.md
Spec 2: /mnt/d/dev/weiqi/lizzieyzy-next/docs/superpowers/specs/2026-04-21-readboard-sync-boundaries-design.md
```

- [ ] **Step 5: Do not commit yet**

```text
No code changed yet. Keep the branch dirty state as-is and move straight into TDD.
```

## Task 1: Add Fox Window Context Parsing In `readboard`

**Files:**
- Create: `/mnt/d/dev/weiqi/readboard/readboard/Core/Protocol/FoxWindowContext.cs`
- Create: `/mnt/d/dev/weiqi/readboard/readboard/Core/Protocol/FoxWindowContextParser.cs`
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/readboard.csproj`
- Modify: `/mnt/d/dev/weiqi/readboard/tests/Readboard.VerificationTests/Readboard.VerificationTests.csproj`
- Create: `/mnt/d/dev/weiqi/readboard/tests/Readboard.VerificationTests/Protocol/FoxWindowContextTitleParsingTests.cs`

- [ ] **Step 1: Write the failing parsing tests**

```csharp
using Xunit;
using readboard;

namespace Readboard.VerificationTests.Protocol
{
    public sealed class FoxWindowContextTitleParsingTests
    {
        [Theory]
        [InlineData("> [高级房1] > 43581号对弈房 观战中[第89手] - 升降级", "43581号", 89)]
        [InlineData("> [高级房1] > 23|890号房间 对弈中[第03手] - 友谊赛 - 数子规则", "23|890号", 3)]
        public void ParseLiveRoom_ExtractsRoomTokenAndDisplayedMove(string title, string expectedToken, int expectedMove)
        {
            FoxWindowContext context = FoxWindowContextParser.Parse(title);

            Assert.Equal(FoxWindowKind.LiveRoom, context.Kind);
            Assert.Equal(expectedToken, context.RoomToken);
            Assert.Equal(expectedMove, context.LiveTitleMove);
        }

        [Fact]
        public void ParseRecordView_UsesTotalMoveAsCurrentMoveWhenOnlyTotalMoveIsPresent()
        {
            FoxWindowContext context = FoxWindowContextParser.Parse("棋谱欣赏 - 黑 Ouuu12138 [2段] 对白 已吃2道 [2段] - 数子规则 - 分先 - 黑中盘胜 - [总333手]");

            Assert.Equal(FoxWindowKind.RecordView, context.Kind);
            Assert.Equal(333, context.RecordCurrentMove);
            Assert.Equal(333, context.RecordTotalMove);
            Assert.True(context.RecordAtEnd);
            Assert.False(string.IsNullOrWhiteSpace(context.TitleFingerprint));
        }
    }
}
```

- [ ] **Step 2: Run the new parsing test and verify it fails**

```bash
pwsh.exe -NoLogo -Command "dotnet test D:\dev\weiqi\readboard\tests\Readboard.VerificationTests\Readboard.VerificationTests.csproj --filter FullyQualifiedName~Readboard.VerificationTests.Protocol.FoxWindowContextTitleParsingTests"
```

Expected: FAIL because `FoxWindowContext` and `FoxWindowContextParser` do not exist yet.

- [ ] **Step 3: Add the minimal parser and DTO implementation**

```csharp
namespace readboard
{
    internal enum FoxWindowKind
    {
        Unknown = 0,
        LiveRoom = 1,
        RecordView = 2
    }

    internal sealed class FoxWindowContext
    {
        public FoxWindowKind Kind { get; set; }
        public string RoomToken { get; set; }
        public int? LiveTitleMove { get; set; }
        public int? RecordCurrentMove { get; set; }
        public int? RecordTotalMove { get; set; }
        public bool RecordAtEnd { get; set; }
        public string TitleFingerprint { get; set; }

        public static FoxWindowContext Unknown()
        {
            return new FoxWindowContext { Kind = FoxWindowKind.Unknown };
        }
    }
}
```

```csharp
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace readboard
{
    internal static class FoxWindowContextParser
    {
        private static readonly Regex RoomTokenPattern = new Regex(@">\s*([^\]\s>]+号)", RegexOptions.Compiled);
        private static readonly Regex LiveMovePattern = new Regex(@"\[第\s*(\d+)\s*手\]", RegexOptions.Compiled);
        private static readonly Regex RecordCurrentPattern = new Regex(@"第\s*(\d+)\s*手", RegexOptions.Compiled);
        private static readonly Regex RecordTotalPattern = new Regex(@"总\s*(\d+)\s*手", RegexOptions.Compiled);

        public static FoxWindowContext Parse(string title)
        {
            if (string.IsNullOrWhiteSpace(title))
                return FoxWindowContext.Unknown();

            Match roomMatch = RoomTokenPattern.Match(title);
            if (roomMatch.Success)
            {
                return new FoxWindowContext
                {
                    Kind = FoxWindowKind.LiveRoom,
                    RoomToken = roomMatch.Groups[1].Value,
                    LiveTitleMove = ParseNullableInt(LiveMovePattern.Match(title))
                };
            }

            Match totalMatch = RecordTotalPattern.Match(title);
            Match currentMatch = RecordCurrentPattern.Match(title);
            if (totalMatch.Success || currentMatch.Success)
            {
                int? totalMove = ParseNullableInt(totalMatch);
                int? currentMove = ParseNullableInt(currentMatch) ?? totalMove;
                return new FoxWindowContext
                {
                    Kind = FoxWindowKind.RecordView,
                    RecordCurrentMove = currentMove,
                    RecordTotalMove = totalMove,
                    RecordAtEnd = !currentMatch.Success && totalMove.HasValue,
                    TitleFingerprint = Fingerprint(title)
                };
            }

            return FoxWindowContext.Unknown();
        }

        private static int? ParseNullableInt(Match match)
        {
            if (!match.Success)
                return null;

            return int.Parse(match.Groups[1].Value);
        }

        private static string Fingerprint(string title)
        {
            string normalized = RecordCurrentPattern.Replace(RecordTotalPattern.Replace(title, "总#手"), "第#手");
            using (SHA1 sha1 = SHA1.Create())
            {
                byte[] bytes = sha1.ComputeHash(Encoding.UTF8.GetBytes(normalized));
                StringBuilder builder = new StringBuilder(bytes.Length * 2);
                foreach (byte b in bytes)
                    builder.Append(b.ToString("x2"));
                return builder.ToString();
            }
        }
    }
}
```

- [ ] **Step 4: Link the new production files into both projects**

```xml
<Compile Include="Core\Protocol\FoxWindowContext.cs" />
<Compile Include="Core\Protocol\FoxWindowContextParser.cs" />
```

```xml
<Compile Include="..\..\readboard\Core\Protocol\FoxWindowContext.cs" Link="Production\Core\Protocol\FoxWindowContext.cs" />
<Compile Include="..\..\readboard\Core\Protocol\FoxWindowContextParser.cs" Link="Production\Core\Protocol\FoxWindowContextParser.cs" />
```

- [ ] **Step 5: Re-run the readboard parsing tests**

```bash
pwsh.exe -NoLogo -Command "dotnet test D:\dev\weiqi\readboard\tests\Readboard.VerificationTests\Readboard.VerificationTests.csproj --filter FullyQualifiedName~Readboard.VerificationTests.Protocol.FoxWindowContextTitleParsingTests"
```

Expected: `Passed!` with 2 tests passing.

## Task 2: Add `readboard` Metadata Lines And One-Shot Force Rebuild

**Files:**
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/Core/Models/ProtocolMessage.cs`
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/Core/Protocol/IReadBoardProtocolAdapter.cs`
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/Core/Protocol/LegacyProtocolAdapter.cs`
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/Core/Protocol/ISyncSessionCoordinator.cs`
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/Core/Protocol/SyncSessionCoordinator.cs`
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/Form1.cs`
- Modify: `/mnt/d/dev/weiqi/readboard/readboard/Form1.Designer.cs`
- Modify: `/mnt/d/dev/weiqi/readboard/tests/Readboard.VerificationTests/Protocol/SyncSessionCoordinatorTests.cs`
- Modify: `/mnt/d/dev/weiqi/readboard/tests/Readboard.VerificationTests/Protocol/LegacyOutboundProtocolContractTests.cs`

- [ ] **Step 1: Write the failing protocol tests for context metadata and one-shot force rebuild**

```csharp
[Fact]
public void SendBoardSnapshot_EmitsFoxContextMetadataBeforeBoardPayload()
{
    FakeTransport transport = new FakeTransport();
    SyncSessionCoordinator coordinator = new SyncSessionCoordinator(transport, new LegacyProtocolAdapter());

    coordinator.SetSyncPlatform("fox");
    coordinator.SetFoxWindowContext(new FoxWindowContext
    {
        Kind = FoxWindowKind.LiveRoom,
        RoomToken = "43581号",
        LiveTitleMove = 89
    });
    coordinator.SendBoardSnapshot(CreateSnapshot("payload-1", 57));

    Assert.Equal(
        new[]
        {
            "syncPlatform fox",
            "roomToken 43581号",
            "liveTitleMove 89",
            "foxMoveNumber 57",
            "re=000",
            "re=111",
            "end"
        },
        transport.SentLines);
}

[Fact]
public void SendBoardSnapshot_ConsumesForceRebuildFlagAfterOneFrame()
{
    FakeTransport transport = new FakeTransport();
    SyncSessionCoordinator coordinator = new SyncSessionCoordinator(transport, new LegacyProtocolAdapter());

    coordinator.ArmForceRebuild();
    coordinator.SendBoardSnapshot(CreateSnapshot("payload-1", 57));
    coordinator.SendBoardSnapshot(CreateSnapshot("payload-2", 58));

    Assert.Contains("forceRebuild", transport.SentLines);
    Assert.Equal(1, transport.SentLines.FindAll(line => line == "forceRebuild").Count);
}
```

- [ ] **Step 2: Run the readboard protocol tests and verify they fail**

```bash
pwsh.exe -NoLogo -Command "dotnet test D:\dev\weiqi\readboard\tests\Readboard.VerificationTests\Readboard.VerificationTests.csproj --filter FullyQualifiedName~Readboard.VerificationTests.SyncSessionCoordinatorTests"
```

Expected: FAIL because the coordinator cannot emit the new lines yet.

- [ ] **Step 3: Add the minimal protocol surface**

```csharp
internal enum ProtocolMessageKind
{
    LegacyLine = 0,
    PlaceMove = 1,
    LossFocus = 2,
    StopInBoard = 3,
    VersionRequest = 4,
    Quit = 5,
    ForceRebuild = 6
}
```

```csharp
internal interface IReadBoardProtocolAdapter
{
    ProtocolMessage CreateSyncPlatformMessage(string platform);
    ProtocolMessage CreateRoomTokenMessage(string roomToken);
    ProtocolMessage CreateLiveTitleMoveMessage(int moveNumber);
    ProtocolMessage CreateRecordCurrentMoveMessage(int moveNumber);
    ProtocolMessage CreateRecordTotalMoveMessage(int moveNumber);
    ProtocolMessage CreateRecordAtEndMessage(bool atEnd);
    ProtocolMessage CreateRecordTitleFingerprintMessage(string fingerprint);
    ProtocolMessage CreateForceRebuildMessage();
}
```

```csharp
public ProtocolMessage CreateSyncPlatformMessage(string platform) => CreateLegacyMessage("syncPlatform " + platform);
public ProtocolMessage CreateRoomTokenMessage(string roomToken) => CreateLegacyMessage("roomToken " + roomToken);
public ProtocolMessage CreateLiveTitleMoveMessage(int moveNumber) => CreateLegacyMessage("liveTitleMove " + moveNumber);
public ProtocolMessage CreateRecordCurrentMoveMessage(int moveNumber) => CreateLegacyMessage("recordCurrentMove " + moveNumber);
public ProtocolMessage CreateRecordTotalMoveMessage(int moveNumber) => CreateLegacyMessage("recordTotalMove " + moveNumber);
public ProtocolMessage CreateRecordAtEndMessage(bool atEnd) => CreateLegacyMessage(atEnd ? "recordAtEnd 1" : "recordAtEnd 0");
public ProtocolMessage CreateRecordTitleFingerprintMessage(string fingerprint) => CreateLegacyMessage("recordTitleFingerprint " + fingerprint);
public ProtocolMessage CreateForceRebuildMessage() => CreateLegacyMessage("forceRebuild");
```

- [ ] **Step 4: Wire `Form1` and `SyncSessionCoordinator` to emit the new metadata**

```csharp
private FoxWindowContext ResolveFoxWindowContext()
{
    if (!IsFoxSyncType(CurrentSyncType) || hwnd == IntPtr.Zero)
        return FoxWindowContext.Unknown();

    WindowDescriptor descriptor;
    if (!FoxWindowDescriptorFactory.TryCreate(hwnd, out descriptor))
        return FoxWindowContext.Unknown();

    return FoxWindowContextParser.Parse(descriptor.Title);
}
```

```csharp
private string syncPlatform = "generic";
private FoxWindowContext foxWindowContext = FoxWindowContext.Unknown();
private bool forceRebuildArmed;

public void SetSyncPlatform(string platform) => syncPlatform = string.IsNullOrWhiteSpace(platform) ? "generic" : platform;
public void SetFoxWindowContext(FoxWindowContext context) => foxWindowContext = context ?? FoxWindowContext.Unknown();
public void ArmForceRebuild() => forceRebuildArmed = true;

private void SendWindowContext(BoardSnapshot snapshot)
{
    SendProtocolMessage(protocolAdapter.CreateSyncPlatformMessage(syncPlatform));
    if (foxWindowContext.Kind == FoxWindowKind.LiveRoom && !string.IsNullOrWhiteSpace(foxWindowContext.RoomToken))
        SendProtocolMessage(protocolAdapter.CreateRoomTokenMessage(foxWindowContext.RoomToken));
    if (foxWindowContext.Kind == FoxWindowKind.LiveRoom && foxWindowContext.LiveTitleMove.HasValue)
        SendProtocolMessage(protocolAdapter.CreateLiveTitleMoveMessage(foxWindowContext.LiveTitleMove.Value));
    if (foxWindowContext.Kind == FoxWindowKind.RecordView && foxWindowContext.RecordCurrentMove.HasValue)
        SendProtocolMessage(protocolAdapter.CreateRecordCurrentMoveMessage(foxWindowContext.RecordCurrentMove.Value));
    if (foxWindowContext.Kind == FoxWindowKind.RecordView && foxWindowContext.RecordTotalMove.HasValue)
        SendProtocolMessage(protocolAdapter.CreateRecordTotalMoveMessage(foxWindowContext.RecordTotalMove.Value));
    if (foxWindowContext.Kind == FoxWindowKind.RecordView)
        SendProtocolMessage(protocolAdapter.CreateRecordAtEndMessage(foxWindowContext.RecordAtEnd));
    if (foxWindowContext.Kind == FoxWindowKind.RecordView && !string.IsNullOrWhiteSpace(foxWindowContext.TitleFingerprint))
        SendProtocolMessage(protocolAdapter.CreateRecordTitleFingerprintMessage(foxWindowContext.TitleFingerprint));
    if (forceRebuildArmed)
    {
        SendProtocolMessage(protocolAdapter.CreateForceRebuildMessage());
        forceRebuildArmed = false;
    }
}
```

- [ ] **Step 5: Re-run the readboard protocol tests**

```bash
pwsh.exe -NoLogo -Command "dotnet test D:\dev\weiqi\readboard\tests\Readboard.VerificationTests\Readboard.VerificationTests.csproj --filter FullyQualifiedName~Readboard.VerificationTests.Protocol"
```

Expected: `Passed!` with the new protocol tests green and no regressions in existing protocol tests.

## Task 3: Introduce Normalized Remote Context And Resume State In Java

**Files:**
- Create: `src/main/java/featurecat/lizzie/analysis/SyncRemoteContext.java`
- Create: `src/main/java/featurecat/lizzie/analysis/SyncResumeState.java`
- Modify: `src/main/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicy.java`
- Modify: `src/main/java/featurecat/lizzie/analysis/SyncConflictTracker.java`
- Modify: `src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java`

- [ ] **Step 1: Write the failing Java policy tests**

```java
@Test
void matchesCurrentMainTrunkAncestorWhenFoxIdentityMatchesAncestor() {
  SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
  BoardHistoryNode root = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);
  BoardHistoryNode ancestor =
      root.add(createMoveHistoryNode(stones(placement(1, 1, Stone.BLACK)), new int[] {1, 1}, Stone.BLACK, false, 1));
  BoardHistoryNode current =
      ancestor.add(
          createMoveHistoryNode(
              stones(placement(1, 1, Stone.BLACK), placement(0, 0, Stone.WHITE)),
              new int[] {0, 0},
              Stone.WHITE,
              true,
              2));

  SyncRemoteContext context = SyncRemoteContext.forFoxLive(OptionalInt.of(1), "43581号", OptionalInt.of(1), false);
  Optional<BoardHistoryNode> matched =
      policy.findMatchingHistoryNode(current, snapshot(ancestor.getData().stones, Optional.empty(), 0), context);

  assertTrue(matched.isPresent());
  assertSame(ancestor, matched.get());
}

@Test
void returnsEmptyWhenFoxLiveFrameHasNoValidMoveNumber() {
  SyncSnapshotRebuildPolicy policy = new SyncSnapshotRebuildPolicy(BOARD_WIDTH);
  BoardHistoryNode current = createNode(emptyStones(), Optional.empty(), Stone.EMPTY);

  SyncRemoteContext context = SyncRemoteContext.forFoxLive(OptionalInt.empty(), "43581号", OptionalInt.empty(), false);
  Optional<BoardHistoryNode> matched =
      policy.findMatchingHistoryNode(current, snapshot(emptyStones(), Optional.empty(), 0), context);

  assertFalse(matched.isPresent());
}
```

- [ ] **Step 2: Run the policy test and verify it fails**

```bash
timeout 60s mvn -Dtest=SyncSnapshotRebuildPolicyTest test
```

Expected: FAIL because `SyncRemoteContext` does not exist and the method signatures are still old.

- [ ] **Step 3: Add the minimal remote-context and resume-state classes**

```java
package featurecat.lizzie.analysis;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

final class SyncRemoteContext {
  enum SyncPlatform { FOX, GENERIC }
  enum WindowKind { LIVE_ROOM, RECORD_VIEW, UNKNOWN }

  final SyncPlatform platform;
  final WindowKind windowKind;
  final OptionalInt foxMoveNumber;
  final Optional<String> roomToken;
  final OptionalInt liveTitleMove;
  final OptionalInt recordCurrentMove;
  final OptionalInt recordTotalMove;
  final boolean recordAtEnd;
  final Optional<String> titleFingerprint;
  final boolean forceRebuild;

  static SyncRemoteContext forFoxLive(
      OptionalInt foxMoveNumber, String roomToken, OptionalInt liveTitleMove, boolean forceRebuild) {
    return new SyncRemoteContext(
        SyncPlatform.FOX,
        WindowKind.LIVE_ROOM,
        foxMoveNumber,
        Optional.ofNullable(roomToken).filter(token -> !token.isEmpty()),
        liveTitleMove,
        OptionalInt.empty(),
        OptionalInt.empty(),
        false,
        Optional.empty(),
        forceRebuild);
  }

  static SyncRemoteContext generic(boolean forceRebuild) {
    return new SyncRemoteContext(
        SyncPlatform.GENERIC,
        WindowKind.UNKNOWN,
        OptionalInt.empty(),
        Optional.empty(),
        OptionalInt.empty(),
        OptionalInt.empty(),
        OptionalInt.empty(),
        false,
        Optional.empty(),
        forceRebuild);
  }

  boolean supportsFoxRecovery() {
    return platform == SyncPlatform.FOX && foxMoveNumber.isPresent();
  }

  boolean conflictsWith(SyncRemoteContext other) {
    if (other == null || platform != other.platform || windowKind != other.windowKind) {
      return true;
    }
    if (windowKind == WindowKind.LIVE_ROOM) {
      return !Objects.equals(roomToken, other.roomToken);
    }
    if (windowKind == WindowKind.RECORD_VIEW) {
      return !Objects.equals(titleFingerprint, other.titleFingerprint)
          || !sameOptionalInt(recordTotalMove, other.recordTotalMove);
    }
    return false;
  }

  private static boolean sameOptionalInt(OptionalInt left, OptionalInt right) {
    return left.isPresent() == right.isPresent() && (!left.isPresent() || left.getAsInt() == right.getAsInt());
  }
}
```

```java
package featurecat.lizzie.analysis;

import featurecat.lizzie.rules.BoardHistoryNode;

final class SyncResumeState {
  final BoardHistoryNode node;
  final SyncRemoteContext remoteContext;

  SyncResumeState(BoardHistoryNode node, SyncRemoteContext remoteContext) {
    this.node = node;
    this.remoteContext = remoteContext;
  }
}
```

- [ ] **Step 4: Change the policy and conflict tracker to use normalized context**

```java
Optional<BoardHistoryNode> findMatchingHistoryNode(
    BoardHistoryNode syncStartNode, int[] snapshotCodes, SyncRemoteContext remoteContext) {
  if (syncStartNode == null || snapshotCodes.length == 0) {
    return Optional.empty();
  }
  if (remoteContext.platform == SyncRemoteContext.SyncPlatform.FOX && !remoteContext.supportsFoxRecovery()) {
    return Optional.empty();
  }

  for (BoardHistoryNode cursor = syncStartNode; cursor != null; cursor = cursor.previous().orElse(null)) {
    if (matchesRemoteIdentity(cursor.getData(), snapshotCodes, remoteContext)) {
      return Optional.of(cursor);
    }
  }
  return Optional.empty();
}

Optional<BoardHistoryNode> findAdjacentMatchFromLastResolvedNode(
    SyncResumeState resumeState, int[] snapshotCodes, SyncRemoteContext remoteContext) {
  if (resumeState == null || resumeState.remoteContext.conflictsWith(remoteContext)) {
    return Optional.empty();
  }
  BoardHistoryNode next = resumeState.node.next().filter(BoardHistoryNode::isMainTrunk).orElse(null);
  if (next != null && matchesRemoteIdentity(next.getData(), snapshotCodes, remoteContext)) {
    return Optional.of(next);
  }
  return Optional.empty();
}
```

```java
final class SyncConflictTracker {
  private String pendingConflictKey = "";
  private boolean pendingConflict;

  Decision evaluate(String conflictKey) {
    if (!pendingConflict || !pendingConflictKey.equals(conflictKey)) {
      pendingConflictKey = conflictKey;
      pendingConflict = true;
      return Decision.HOLD;
    }
    clear();
    return Decision.REBUILD;
  }

  void clear() {
    pendingConflictKey = "";
    pendingConflict = false;
  }
}
```

- [ ] **Step 5: Re-run the Java policy tests**

```bash
timeout 60s mvn -Dtest=SyncSnapshotRebuildPolicyTest test
```

Expected: `BUILD SUCCESS` with the new ancestor-only and missing-fox tests passing.

## Task 4: Replace `ReadBoard` Decision Flow And Tracker Lifecycle

**Files:**
- Modify: `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
- Modify: `src/main/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicy.java`
- Modify: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`

- [ ] **Step 1: Write the failing decision-flow tests**

```java
@Test
void stopSyncThenResyncAtSameFoxMoveKeepsCurrentNodeAndDoesNotRebuild() throws Exception {
  try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
    HistoryPath path = buildHistory(harness.board, placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));
    BoardHistoryNode current = harness.board.getHistory().getMainEnd();
    setField(harness.readBoard, "resumeState", new SyncResumeState(current, SyncRemoteContext.forFoxLive(OptionalInt.of(2), "43581号", OptionalInt.of(2), false)));

    harness.readBoard.parseLine("stopsync");
    harness.readBoard.parseLine("syncPlatform fox");
    harness.readBoard.parseLine("roomToken 43581号");
    harness.readBoard.parseLine("liveTitleMove 2");
    harness.readBoard.parseLine("foxMoveNumber 2");
    harness.sync(snapshot(current.getData().stones, Optional.of(new int[] {1, 0}), 4));

    assertSame(current, harness.board.getHistory().getMainEnd());
    assertEquals(0, harness.leelaz.clearCount);
  }
}

@Test
void forceRebuildLineSkipsHoldAndRebuildsImmediately() throws Exception {
  Stone[] target = stones(placement(0, 0, Stone.BLACK), placement(1, 0, Stone.WHITE));

  try (SyncHarness harness = SyncHarness.create(false, emptyHistory())) {
    harness.readBoard.parseLine("syncPlatform fox");
    harness.readBoard.parseLine("roomToken 43581号");
    harness.readBoard.parseLine("foxMoveNumber 58");
    harness.readBoard.parseLine("forceRebuild");
    harness.sync(snapshot(target, Optional.empty(), Stone.EMPTY));

    assertTrue(harness.board.getHistory().getMainEnd().getData().isSnapshotNode());
    assertEquals(1, harness.leelaz.clearCount);
  }
}
```

- [ ] **Step 2: Run the decision tests and verify they fail**

```bash
timeout 60s mvn -Dtest=ReadBoardSyncDecisionTest test
```

Expected: FAIL because `ReadBoard` still clears all trackers together and does not parse the new context lines.

- [ ] **Step 3: Parse the new protocol lines and separate tracker lifecycle**

```java
private SyncRemoteContext pendingRemoteContext = SyncRemoteContext.generic(false);
private SyncResumeState resumeState;

public void parseLine(String line) {
  if (line.startsWith("syncPlatform ")) {
    pendingRemoteContext = pendingRemoteContext.withPlatform(parsePlatform(line));
  }
  if (line.startsWith("roomToken ")) {
    pendingRemoteContext = pendingRemoteContext.withRoomToken(line.substring("roomToken ".length()).trim());
  }
  if (line.startsWith("liveTitleMove ")) {
    pendingRemoteContext = pendingRemoteContext.withLiveTitleMove(parseOptionalInt(line, "liveTitleMove "));
  }
  if (line.startsWith("recordCurrentMove ")) {
    pendingRemoteContext = pendingRemoteContext.withRecordCurrentMove(parseOptionalInt(line, "recordCurrentMove "));
  }
  if (line.startsWith("recordTotalMove ")) {
    pendingRemoteContext = pendingRemoteContext.withRecordTotalMove(parseOptionalInt(line, "recordTotalMove "));
  }
  if (line.startsWith("recordAtEnd ")) {
    pendingRemoteContext = pendingRemoteContext.withRecordAtEnd(line.endsWith("1"));
  }
  if (line.startsWith("recordTitleFingerprint ")) {
    pendingRemoteContext = pendingRemoteContext.withTitleFingerprint(line.substring("recordTitleFingerprint ".length()).trim());
  }
  if (line.equals("forceRebuild")) {
    pendingRemoteContext = pendingRemoteContext.withForceRebuild(true);
  }
}

private void resetActiveSyncState() {
  conflictTracker.clear();
  historyJumpTracker.clear();
  waitingForReadBoardLocalMoveAck = false;
  pendingRemoteContext = SyncRemoteContext.generic(false);
}

private void clearResumeState() {
  resumeState = null;
}
```

- [ ] **Step 4: Route sync through the new policy and delete the old dead path as each replacement lands**

```java
private CompleteSnapshotRecovery resolveCompleteSnapshotRecovery(
    BoardHistoryNode syncStartNode,
    Stone[] syncStartStones,
    int[] snapshotCodes,
    SyncSnapshotClassifier.SnapshotDelta snapshotDelta) {
  SyncRemoteContext remoteContext = currentPendingRemoteContext();
  if (remoteContext.forceRebuild) {
    return CompleteSnapshotRecovery.FORCE_REBUILD;
  }

  Optional<BoardHistoryNode> ancestorMatch =
      rebuildPolicy().findMatchingHistoryNode(syncStartNode, snapshotCodes, remoteContext);
  if (ancestorMatch.isPresent()) {
    moveToAnyPositionWithoutTracking(ancestorMatch.get());
    return CompleteSnapshotRecovery.NO_CHANGE;
  }

  Optional<BoardHistoryNode> adjacent =
      rebuildPolicy().findAdjacentMatchFromLastResolvedNode(resumeState, snapshotCodes, remoteContext);
  if (adjacent.isPresent()) {
    return CompleteSnapshotRecovery.SINGLE_MOVE_RECOVERY;
  }

  if (snapshotDelta.hasMarker() && tryApplySingleMoveRecovery(syncStartNode, syncStartStones, snapshotCodes)) {
    return CompleteSnapshotRecovery.SINGLE_MOVE_RECOVERY;
  }

  if (shouldHoldConflictingSnapshot(remoteContext, snapshotCodes)) {
    return CompleteSnapshotRecovery.HOLD;
  }
  return CompleteSnapshotRecovery.FORCE_REBUILD;
}

private boolean shouldHoldConflictingSnapshot(SyncRemoteContext remoteContext, int[] snapshotCodes) {
  String conflictKey = rebuildPolicy().buildConflictKey(snapshotCodes, remoteContext);
  return conflictTracker.evaluate(conflictKey) == SyncConflictTracker.Decision.HOLD;
}

private void rememberResolvedSnapshotNode(BoardHistoryNode resolvedNode) {
  resumeState = new SyncResumeState(resolvedNode, currentPendingRemoteContext().withoutForceRebuild());
}
```

- [ ] **Step 5: Re-run the Java sync-decision tests**

```bash
timeout 60s mvn -Dtest=ReadBoardSyncDecisionTest test
```

Expected: `BUILD SUCCESS` with the new resume-state and force-rebuild tests passing; remove any now-unused helper or field only after the tests go green.

## Task 5: Explicitly Resume Analysis After Sync Lands

**Files:**
- Modify: `src/main/java/featurecat/lizzie/analysis/ReadBoard.java`
- Create: `src/test/java/featurecat/lizzie/analysis/ReadBoardEngineResumeTest.java`
- Modify: `src/test/java/featurecat/lizzie/analysis/LeelazLoadSgfResponseBindingTest.java`

- [ ] **Step 1: Write the failing analysis-resume tests**

```java
@Test
void rebuildFromSnapshotSchedulesAnalysisAfterLoadSgfConsumption() throws Exception {
  try (EngineResumeHarness harness = EngineResumeHarness.create()) {
    harness.readBoard.rebuildFromSnapshot(harness.syncStartNode, harness.snapshotCodes, harness.snapshotDelta, OptionalInt.of(58));

    assertEquals(1, harness.leelaz.loadSgfCalls());
    assertEquals(1, harness.leelaz.resumeAnalysisCalls());
    assertTrue(harness.leelaz.resumeAnalysisAfterLoad());
  }
}

@Test
void firstNoChangeFrameAfterRestartStillEnsuresAnalysisIsRunning() throws Exception {
  try (EngineResumeHarness harness = EngineResumeHarness.create()) {
    harness.armRestartedSession();
    harness.syncNoChangeFrame();

    assertEquals(1, harness.leelaz.ensureAnalysisCalls());
  }
}
```

- [ ] **Step 2: Run the analysis-resume tests and verify they fail**

```bash
timeout 60s mvn -Dtest=ReadBoardEngineResumeTest,LeelazLoadSgfResponseBindingTest test
```

Expected: FAIL because `ReadBoard` has no explicit post-sync analysis hook.

- [ ] **Step 3: Add a dedicated post-sync analysis hook in `ReadBoard`**

```java
private long syncAnalysisEpoch = 0L;

private void scheduleAnalysisResume(BoardHistoryNode targetNode) {
  long epoch = ++syncAnalysisEpoch;
  Lizzie.frame.scheduleResumeAnalysisAfterLoad(
      () -> {
        if (epoch != syncAnalysisEpoch) {
          return;
        }
        if (Lizzie.board.getHistory().getCurrentHistoryNode() != targetNode) {
          moveToAnyPositionWithoutTracking(targetNode);
        }
        Lizzie.frame.resumeAnalysisAfterLoad();
      });
}

private void rebuildFromSnapshot(
    BoardHistoryNode syncStartNode,
    int[] snapshotCodes,
    SyncSnapshotClassifier.SnapshotDelta snapshotDelta,
    OptionalInt foxMoveNumber) {
  ...
  syncEngineToRebuiltSnapshot(rebuiltHistory.getCurrentHistoryNode());
  scheduleAnalysisResume(rebuiltHistory.getCurrentHistoryNode());
}
```

- [ ] **Step 4: Call the same hook for `SINGLE_MOVE_RECOVERY` and restart-session `NO_CHANGE`**

```java
private void onSingleMoveRecoveryApplied(BoardHistoryNode resolvedNode) {
  rememberResolvedSnapshotNode(resolvedNode);
  scheduleAnalysisResume(resolvedNode);
}

private void onRestartedSessionNoChange(BoardHistoryNode resolvedNode) {
  rememberResolvedSnapshotNode(resolvedNode);
  scheduleAnalysisResume(resolvedNode);
}
```

- [ ] **Step 5: Run the focused analysis tests**

```bash
timeout 60s mvn -Dtest=ReadBoardEngineResumeTest,LeelazLoadSgfResponseBindingTest test
```

Expected: `BUILD SUCCESS` with explicit analysis resume covered for rebuild, first-frame `NO_CHANGE`, and single-step recovery.

## Task 6: Run Regression Suites, Clean Only Replaced Code, And Perform Manual Acceptance

**Files:**
- Modify: only the files touched in Tasks 1-5
- Test: `src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java`
- Test: `src/test/java/featurecat/lizzie/analysis/ReadBoardEngineResumeTest.java`
- Test: `/mnt/d/dev/weiqi/readboard/tests/Readboard.VerificationTests/Protocol/FoxWindowContextTitleParsingTests.cs`
- Test: `/mnt/d/dev/weiqi/readboard/tests/Readboard.VerificationTests/Protocol/SyncSessionCoordinatorTests.cs`

- [ ] **Step 1: Remove only the now-unused sync code paths that the new tests made unreachable**

```java
// Delete after the new tests are green:
// - any stubbed lastResolved-adjacent branch that always returns Optional.empty()
// - any raw-snapshot HOLD comparison that still relies on Arrays.equals(snapshotCodes, ...)
// - any reset helper that clears resume state during readboard start/clear without an explicit caller
```

- [ ] **Step 2: Run the focused Java regression suite**

```bash
timeout 60s mvn -Dtest=SyncSnapshotRebuildPolicyTest,ReadBoardSyncDecisionTest,ReadBoardEngineResumeTest,LeelazLoadSgfResponseBindingTest test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run the focused readboard protocol regression suite**

```bash
pwsh.exe -NoLogo -Command "dotnet test D:\dev\weiqi\readboard\tests\Readboard.VerificationTests\Readboard.VerificationTests.csproj --filter FullyQualifiedName~Readboard.VerificationTests.Protocol"
```

Expected: `Passed!`.

- [ ] **Step 4: Execute the P0 manual acceptance matrix from the spec**

```text
Run and verify:
1. MAN-FOX-LIVE-001
2. MAN-FOX-LIVE-002
3. MAN-FOX-LIVE-003
4. MAN-FOX-LIVE-004
5. MAN-FOX-LIVE-005
6. MAN-FOX-RECORD-001
7. MAN-FOX-RECORD-002
8. MAN-FORCE-001
```

Expected: every P0 scenario behaves exactly as documented in `/mnt/d/dev/weiqi/lizzieyzy-next/docs/superpowers/specs/2026-04-21-readboard-sync-boundaries-design.md`.

- [ ] **Step 5: Commit the code changes in narrow batches**

```bash
git add src/main/java/featurecat/lizzie/analysis/ReadBoard.java \
        src/main/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicy.java \
        src/main/java/featurecat/lizzie/analysis/SyncConflictTracker.java \
        src/main/java/featurecat/lizzie/analysis/SyncRemoteContext.java \
        src/main/java/featurecat/lizzie/analysis/SyncResumeState.java \
        src/test/java/featurecat/lizzie/analysis/SyncSnapshotRebuildPolicyTest.java \
        src/test/java/featurecat/lizzie/analysis/ReadBoardSyncDecisionTest.java \
        src/test/java/featurecat/lizzie/analysis/ReadBoardEngineResumeTest.java \
        src/test/java/featurecat/lizzie/analysis/LeelazLoadSgfResponseBindingTest.java
git commit -m "fix(analysis): 恢复读盘同步续接与强制重建边界"
```

```bash
git -C /mnt/d/dev/weiqi/readboard add readboard/Core/Protocol/FoxWindowContext.cs \
    readboard/Core/Protocol/FoxWindowContextParser.cs \
    readboard/Core/Models/ProtocolMessage.cs \
    readboard/Core/Protocol/IReadBoardProtocolAdapter.cs \
    readboard/Core/Protocol/LegacyProtocolAdapter.cs \
    readboard/Core/Protocol/ISyncSessionCoordinator.cs \
    readboard/Core/Protocol/SyncSessionCoordinator.cs \
    readboard/Form1.cs \
    readboard/Form1.Designer.cs \
    readboard/readboard.csproj \
    tests/Readboard.VerificationTests/Readboard.VerificationTests.csproj \
    tests/Readboard.VerificationTests/Protocol/FoxWindowContextTitleParsingTests.cs \
    tests/Readboard.VerificationTests/Protocol/SyncSessionCoordinatorTests.cs \
    tests/Readboard.VerificationTests/Protocol/LegacyOutboundProtocolContractTests.cs
git -C /mnt/d/dev/weiqi/readboard commit -m "fix(protocol): 补齐野狐窗口上下文与单次强制重建信号"
```

## Self-Review

- Spec coverage:
  - Fox live/record identity, room token parsing, missing-fox fallback: Tasks 1-4.
  - Resume-state lifecycle and stop/start behavior: Task 4.
  - One-shot force rebuild button and protocol: Task 2.
  - Generic conservative mode: Task 4 tests plus Task 6 regression.
  - Explicit analysis resume: Task 5.
- Placeholder scan:
  - No `TODO`/`TBD` placeholders remain.
  - Every code-changing task includes concrete file paths, code snippets, and commands.
- Type consistency:
  - The plan consistently uses `SyncRemoteContext`, `SyncResumeState`, `FoxWindowContext`, and `FoxWindowContextParser`.
  - The same protocol line names are used throughout: `syncPlatform`, `roomToken`, `liveTitleMove`, `recordCurrentMove`, `recordTotalMove`, `recordAtEnd`, `recordTitleFingerprint`, `forceRebuild`.
