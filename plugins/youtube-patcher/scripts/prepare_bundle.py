"""Prepare the pinned genuine release for Android using SDK D8 (no runtime shim)."""
import argparse, hashlib, json, os, pathlib, subprocess, sys, tempfile, urllib.error, urllib.request, zipfile

URL = 'https://github.com/Anezium/morphe-patches/releases/download/v1.39.1-rokid.2/patches-1.39.1-rokid.2.mpp'
EXPECTED = 'd07e9aae4a5b9fffdd8e2eb81dfcdf8f0305805a9b777ac094a5065d96df6601'
OFFLINE_HINT = 'Pass -PpatchBundleInput=/absolute/path/to/patches-1.39.1-rokid.2.mpp to build offline.'


def split_classpath(value, separator=os.pathsep):
    # Gradle joins with File.pathSeparator; ':' would also split 'C:\...' on Windows.
    return [entry for entry in value.split(separator) if entry]


def download(url, target):
    try:
        with urllib.request.urlopen(url, timeout=120) as response:
            target.write_bytes(response.read())
    except (urllib.error.URLError, OSError) as error:
        raise SystemExit(f'Could not download the pinned patch bundle from {url}: {error}. {OFFLINE_HINT}')


def verify(source, expected=EXPECTED):
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    if digest != expected:
        raise SystemExit(f'{source} is not the pinned rokid.2 release: SHA-256 {digest}, expected {expected}.')
    return digest


def write_prepared(source, dex_files, target):
    # Fixed metadata keeps bundled.mpp, and the hash recorded in bundled.json, identical across builds.
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as prepared:
        entries = original.infolist()
        stamp = max((entry.date_time for entry in entries), default=(1980, 1, 1, 0, 0, 0))
        for entry in entries:
            prepared.writestr(entry, original.read(entry.filename))
        for file in sorted(dex_files, key=lambda path: path.name):
            info = zipfile.ZipInfo(file.name, date_time=stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o644 << 16
            prepared.writestr(info, file.read_bytes())


def main(argv=None):
    p = argparse.ArgumentParser()
    for name in ('input', 'output', 'java', 'd8', 'android', 'classpath'):
        p.add_argument('--' + name, required=True)
    a = p.parse_args(argv)
    out = pathlib.Path(a.output)
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=out.parent) as scratch:
        scratch = pathlib.Path(scratch)
        source = pathlib.Path(a.input) if a.input else scratch / 'source.mpp'
        if a.input and not source.is_file():
            raise SystemExit(f'patchBundleInput does not exist: {source}')
        if not a.input:
            download(URL, source)
        digest = verify(source)
        original_copy = out.parent.parent / 'patch-source' / 'source.mpp'
        original_copy.parent.mkdir(parents=True, exist_ok=True)
        original_copy.write_bytes(source.read_bytes())
        dex = scratch / 'dex'
        dex.mkdir()
        # D8 recognizes JAR/ZIP extensions, not .mpp.
        jar = scratch / 'patches.jar'
        jar.write_bytes(source.read_bytes())
        cmd = [a.java, '-cp', a.d8, 'com.android.tools.r8.D8', '--release', '--min-api', '30', '--lib', a.android, '--output', str(dex)]
        for entry in split_classpath(a.classpath):
            cmd.extend(['--classpath', entry])
        cmd.append(str(jar))
        if subprocess.run(cmd).returncode != 0:
            raise SystemExit('D8 failed to convert the pinned patch bundle; see the D8 output above.')
        target = out / 'bundled.mpp'
        write_prepared(source, list(dex.glob('*.dex')), target)
        with zipfile.ZipFile(target) as prepared:
            if 'classes.dex' not in prepared.namelist():
                raise SystemExit('D8 did not produce executable Android patches')
        (out / 'bundled.json').write_text(json.dumps({'version': '1.39.1-rokid.2', 'source_sha256': digest,
            'sha256': hashlib.sha256(target.read_bytes()).hexdigest(), 'download_url': URL}))
        print('Prepared genuine rokid.2 for Android; source SHA-256:', digest)


if __name__ == '__main__':
    sys.exit(main())
