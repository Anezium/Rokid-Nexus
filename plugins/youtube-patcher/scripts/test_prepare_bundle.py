"""Host checks for prepare_bundle.py: python3 -m unittest discover -s plugins/youtube-patcher/scripts"""
import hashlib, os, pathlib, shutil, tempfile, time, unittest, urllib.error, zipfile
from unittest import mock

import prepare_bundle


class SplitClasspathTest(unittest.TestCase):
    def test_windows_separator_keeps_drive_letters(self):
        self.assertEqual(prepare_bundle.split_classpath(r'C:\g\a.jar;D:\g\b.jar', ';'), [r'C:\g\a.jar', r'D:\g\b.jar'])

    def test_posix_separator_and_empty_entries(self):
        self.assertEqual(prepare_bundle.split_classpath('/g/a.jar::/g/b.jar:', ':'), ['/g/a.jar', '/g/b.jar'])

    def test_default_is_platform_separator(self):
        self.assertEqual(prepare_bundle.split_classpath(os.pathsep.join(['a.jar', 'b.jar'])), ['a.jar', 'b.jar'])


class PreparedBundleTest(unittest.TestCase):
    def setUp(self):
        self.dir = pathlib.Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)
        self.source = self.dir / 'source.mpp'
        with zipfile.ZipFile(self.source, 'w', zipfile.ZIP_DEFLATED) as z:
            z.writestr(zipfile.ZipInfo('app/Patch.class', (2026, 4, 18, 0, 58, 0)), b'class')
            z.writestr(zipfile.ZipInfo('license/NOTICE.TXT', (2026, 4, 17, 1, 2, 4)), b'notice')

    def dex(self, name, mtime):
        path = self.dir / name
        path.write_bytes(b'dex\n035\0' + name.encode())
        os.utime(path, (mtime, mtime))
        return path

    def test_output_is_byte_identical_regardless_of_dex_mtime_and_order(self):
        first, second = self.dir / 'first.mpp', self.dir / 'second.mpp'
        prepare_bundle.write_prepared(self.source, [self.dex('classes2.dex', 1), self.dex('classes.dex', 1)], first)
        time.sleep(2)
        prepare_bundle.write_prepared(self.source, [self.dex('classes.dex', time.time()), self.dex('classes2.dex', time.time())], second)
        self.assertEqual(hashlib.sha256(first.read_bytes()).digest(), hashlib.sha256(second.read_bytes()).digest())
        with zipfile.ZipFile(first) as z:
            self.assertEqual(z.namelist(), ['app/Patch.class', 'license/NOTICE.TXT', 'classes.dex', 'classes2.dex'])
            self.assertEqual(z.getinfo('classes.dex').date_time, (2026, 4, 18, 0, 58, 0))

    def test_hash_mismatch_names_both_hashes(self):
        with self.assertRaises(SystemExit) as raised:
            prepare_bundle.verify(self.source)
        self.assertIn(prepare_bundle.EXPECTED, str(raised.exception))
        self.assertIn(hashlib.sha256(self.source.read_bytes()).hexdigest(), str(raised.exception))

    def test_download_failure_is_actionable(self):
        with mock.patch('urllib.request.urlopen', side_effect=urllib.error.URLError('refused')):
            with self.assertRaises(SystemExit) as raised:
                prepare_bundle.download(prepare_bundle.URL, self.dir / 'download.mpp')
        self.assertIn('patchBundleInput', str(raised.exception))
        self.assertIn(prepare_bundle.URL, str(raised.exception))


if __name__ == '__main__':
    unittest.main()
