#!/usr/bin/env python3

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import build_katago_cuda_dependencies as sdk
from build_katago_macos_dependencies import digest, inventory
from build_katago_source import build
from package_katago_source_windows import CUDA_SYSTEM_LIBRARIES, inspect_pe, verify_runtime_closure
import prepare_bundled_nvidia_runtime as shipped


class CudaSourceTest(unittest.TestCase):
    def test_locks_preserve_shipped_cuda_and_nvrtc(self):
        lock = json.loads(sdk.LOCK_PATH.read_text())
        self.assertEqual('12.8.0', lock['cudaRelease'])
        dependencies = {item['name']: item for item in lock['dependencies']}
        self.assertEqual(8, len(dependencies))
        self.assertEqual(shipped.CUDA_12_8_NVRTC_VERSION, dependencies['cuda_nvrtc']['version'])
        self.assertEqual(shipped.CUDA_12_8_NVRTC_SHA256, dependencies['cuda_nvrtc']['sha256'])
        self.assertEqual('9.8.0.87', dependencies['cudnn']['version'])
        self.assertEqual([50, 52, 53, 60, 61, 62, 70, 72, 75, 80, 86, 87, 90, 120], lock['gpuArchitectures'])
        for item in dependencies.values():
            self.assertRegex(item['sha256'], r'^[0-9a-f]{64}$')
            self.assertGreater(item['sizeBytes'], 0)
            self.assertTrue(item['url'].startswith('https://'))

    def test_runtime_includes_dynamic_attention_dependencies_not_old_engine(self):
        files = sdk.runtime_files()
        self.assertEqual(24, len(files))
        self.assertEqual(len(files), len(set(files)))
        for name in ('nvrtc64_120_0.dll', 'nvrtc64_120_0.alt.dll', 'nvrtc-builtins64_128.dll', 'cudnn_graph64_9.dll',
                     'cudnn_engines_runtime_compiled64_9.dll', 'nvJitLink_120_0.dll', 'nvblas64_12.dll'):
            self.assertIn(name, files)
        for name in ('katago.exe', 'nvcc.exe', 'nvcuda.dll', 'nvvm64_40_0.dll', 'libcrypto-3-x64.dll'):
            self.assertNotIn(name, files)

    def test_compiler_and_libraries_must_come_from_sealed_sdk(self):
        root = Path('/explicit sdk')
        options = sdk.engine_options(root)
        for option in ('-DCMAKE_CUDA_COMPILER=', '-DCUDAToolkit_ROOT=', '-DCUDNN_INCLUDE_DIR=', '-DCUDNN_LIBRARY='):
            actual = next(value for value in options if value.startswith(option))
            self.assertIn(str(root.resolve()), actual)
        self.assertFalse(any('allow-unsupported-compiler' in option for option in options))
        with patch.dict('os.environ', {'CUDA_PATH': 'foreign', 'CUDA_PATH_V13_0': 'foreign',
                                      'NVCC_APPEND_FLAGS': '--allow-unsupported-compiler', 'CUDACXX': 'foreign'}):
            env = sdk.environment(root)
        self.assertFalse(any(key in env for key in ('CUDA_PATH', 'CUDA_PATH_V13_0', 'NVCC_APPEND_FLAGS', 'CUDACXX')))

    def test_provider_rejects_wrong_identity_or_modified_inventory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'header.h').write_text('verified')
            original = dict(schemaVersion=1, status='PASS', system='Windows', arch='x86_64',
                            provider='cuda', lockSha256=digest(sdk.LOCK_PATH),
                            commonLockSha256=digest(sdk.common.LOCK_PATH), nvccVersion='12.8.61',
                            configuration=sdk.engine_options(root), files=inventory(root))
            for key, value in ((None, None), ('provider', 'tensorrt'), ('nvccVersion', '13.0'),
                               ('lockSha256', 'wrong'), ('commonLockSha256', 'wrong'),
                               ('configuration', []), ('files', []), ('status', 'FAIL')):
                receipt = dict(original)
                if key: receipt[key] = value
                (root / 'sdk-receipt.json').write_text(json.dumps(receipt))
                if key:
                    with self.assertRaises(ValueError): sdk.verify_sdk(root)
                else:
                    self.assertEqual('PASS', sdk.verify_sdk(root)['status'])

    def test_no_sdk_is_rejected_before_build_creates_output(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch('build_katago_source.check_host'), patch('build_katago_source.check_source'):
                with self.assertRaisesRegex(ValueError, 'verified pinned'):
                    build(root / 'source', root / 'output', sdk.TARGET, [], 2)
            self.assertFalse((root / 'output').exists())

    def test_tampered_archive_rejected_before_provider_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'archive').write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'SHA-256'):
                sdk.install_runtime({'cuda_nvcc': root / 'archive'}, root / 'prefix')
            self.assertFalse((root / 'prefix').exists())

    def test_all_runtime_files_mandatory_including_delayed_loads(self):
        files = set(sdk.runtime_files())
        verify_runtime_closure({}, files, files)
        for name in files:
            with self.subTest(name=name), self.assertRaises(ValueError):
                verify_runtime_closure({}, files, files - {name})

    def test_driver_dependency_is_cuda_only(self):
        imports = ' KERNEL32.dll\n nvcuda.dll\n cudnn64_9.dll\n'
        inspect_pe('8664 machine (x64)', imports, set(sdk.runtime_files()), CUDA_SYSTEM_LIBRARIES)
        with self.assertRaises(ValueError):
            inspect_pe('8664 machine (x64)', imports, set(sdk.runtime_files()))

    def archive(self, root, names):
        path = root / 'archive.zip'
        with zipfile.ZipFile(path, 'w') as bundle:
            for name in names:
                bundle.writestr(name, b'fixture')
        return path

    def test_archive_traversal_duplicate_and_alternate_stream_rejected(self):
        for names in (['sdk/../escape.h'], ['sdk/include/x.h', 'sdk/include/X.h'],
                      ['sdk/include/x.h:stream'], ['sdk/./include/x.h'], ['/sdk/x.h'],
                      ['sdk/include/x.h', 'other/include/y.h'], ['sdk/include/x.h.']):
            with self.subTest(names=names), tempfile.TemporaryDirectory() as directory:
                archive = self.archive(Path(directory), names)
                with zipfile.ZipFile(archive) as bundle, self.assertRaises(ValueError):
                    sdk.validated_entries(bundle)

    def test_cannot_overwrite_common_or_mix_component_headers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            archive = self.archive(root, ['sdk/LICENSE', 'sdk/include/header.h'])
            dependency = dict(name='headers', destination='cuda')
            sdk.install_archive(archive, root / 'prefix', dependency)
            self.assertEqual(b'fixture', (root / 'prefix/cuda/include/header.h').read_bytes())
            with self.assertRaisesRegex(ValueError, 'overwrite'):
                sdk.install_archive(archive, root / 'prefix', dependency)

    def test_no_undeclared_runtime_or_missing_notice(self):
        for names in (['sdk/LICENSE', 'sdk/bin/unexpected.dll'], ['sdk/include/header.h']):
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with self.assertRaises(ValueError):
                    sdk.install_archive(self.archive(root, names), root / 'prefix', dict(name='bad', destination='cuda'))

    def test_nvrtc_alternate_dll_is_preserved_without_replacing_default_compiler(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            names = ['nvrtc64_120_0.dll', 'nvrtc64_120_0.alt.dll', 'nvrtc-builtins64_128.dll']
            archive = self.archive(root, ['sdk/LICENSE'] + ['sdk/bin/' + name for name in names])
            sdk.install_archive(archive, root / 'prefix', dict(name='cuda_nvrtc', destination='cuda'))
            self.assertEqual(set(names), {path.name for path in (root / 'prefix/runtime').iterdir()})
            for name in names:
                self.assertEqual(b'fixture', (root / 'prefix/cuda/bin' / name).read_bytes())


if __name__ == '__main__':
    unittest.main()
