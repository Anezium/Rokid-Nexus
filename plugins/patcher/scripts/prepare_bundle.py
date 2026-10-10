"""Prepare the pinned genuine release for Android using SDK D8 (no runtime shim)."""
import argparse, hashlib, json, os, pathlib, subprocess, sys, tempfile, urllib.error, urllib.request, zipfile

URL = 'https://github.com/Anezium/morphe-patches/releases/download/v1.39.1-rokid.3/patches-1.39.1-rokid.3.mpp'
EXPECTED = 'd2b7de48fe7d58b04027754ad7bbd79f0cdff5b2b61364d652b2f4684185adf7'
VERSION = URL.rsplit("/", 2)[1].removeprefix("v")
OFFLINE_HINT = 'Pass -PpatchBundleInput=/absolute/path/to/patches-1.39.1-rokid.3.mpp to build offline.'


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
        raise SystemExit(f'{source} is not the pinned patch bundle: SHA-256 {digest}, expected {expected}.')
    return digest


def resolve_source(input_path, scratch, config=None):
    source = pathlib.Path(input_path) if input_path else scratch / 'source.mpp'
    if input_path:
        if not source.is_file():
            raise SystemExit(f'Patch bundle input does not exist: {source}')
    else:
        url = config.get('download_url') if config else URL
        if config and (config.get('unpublished', True) or not url):
            raise SystemExit('The Reddit preview is unpublished. Pass -PredditPatchBundleInput=/absolute/path/to/source.mpp.')
        download(url, source)
    digest = verify(source, config['source_sha256'] if config else EXPECTED)
    return source, digest


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
    p.add_argument('--pin-file')
    a = p.parse_args(argv)
    config = json.loads(pathlib.Path(a.pin_file).read_text()) if a.pin_file else None
    version = config['version'] if config else VERSION
    stem = config['asset_stem'] if config else 'bundled'
    if stem not in ('bundled', 'reddit'):
        raise SystemExit('Unsupported prepared bundle asset name.')
    out = pathlib.Path(a.output)
    out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=out.parent) as scratch:
        scratch = pathlib.Path(scratch)
        source, digest = resolve_source(a.input, scratch, config)
        with zipfile.ZipFile(source) as original:
            if 'classes.dex' in original.namelist():
                raise SystemExit('Use the JVM source bundle, before :patches:buildAndroid adds root DEX.')
        original_copy = out.parent.parent / 'patch-source' / ('reddit-source.mpp' if config else 'source.mpp')
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
        target = out / f'{stem}.mpp'
        write_prepared(source, list(dex.glob('*.dex')), target)
        with zipfile.ZipFile(target) as prepared:
            if 'classes.dex' not in prepared.namelist():
                raise SystemExit('D8 did not produce executable Android patches')
        metadata = {'version': version, 'source_sha256': digest,
            'sha256': hashlib.sha256(target.read_bytes()).hexdigest()}
        if config:
            metadata['unpublished'] = config.get('unpublished', True)
            if config.get('download_url'):
                metadata['download_url'] = config['download_url']
        else:
            metadata['download_url'] = URL
        (out / f'{stem}.json').write_text(json.dumps(metadata))
        print(f'Prepared {version} for Android; source SHA-256:', digest)


if __name__ == '__main__':
    sys.exit(main())
