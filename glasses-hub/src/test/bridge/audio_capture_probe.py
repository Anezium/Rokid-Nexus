"""Owner-operated loopback probe. No ADB, installation or audio playback is performed here."""
import argparse
import json
import socket
import struct
import threading
import time


def read_exact(stream, length):
    data = bytearray()
    while len(data) < length:
        chunk = stream.recv(length - len(data))
        if not chunk:
            raise EOFError
        data.extend(chunk)
    return bytes(data)


def probe(port, keepalive_seconds=None):
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", port))
        listener.listen(1)
        print(f"Listening on {port}", flush=True)
        connection, _ = listener.accept()
        with connection:
            print("Token:", read_exact(connection, 32).hex(), flush=True)
            started = time.monotonic()
            finished = threading.Event()
            counters = [0, 0]

            def keepalive():
                while not finished.is_set():
                    if keepalive_seconds is None or time.monotonic() - started < keepalive_seconds:
                        try:
                            connection.sendall(b"\x10\x00\x00")
                        except OSError:
                            return
                    print(f"packets={counters[0]} bytes={counters[1]}", flush=True)
                    counters[:] = [0, 0]
                    finished.wait(1)

            threading.Thread(target=keepalive, daemon=True).start()
            try:
                while True:
                    kind, length = struct.unpack(">BH", read_exact(connection, 3))
                    if length > 4096:
                        raise ValueError("Oversized local record")
                    body = read_exact(connection, length)
                    if kind == 1:
                        print("HEADER", json.loads(body), flush=True)
                    elif kind == 2:
                        counters[0] += 1
                        counters[1] += length
                    elif kind == 3:
                        print("STATUS", json.loads(body), flush=True)
                    else:
                        raise ValueError("Unexpected record")
            except EOFError:
                print(f"EOF after {time.monotonic() - started:.2f}s", flush=True)
            finally:
                finished.set()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("port", type=int)
    parser.add_argument("--keepalive-seconds", type=float)
    arguments = parser.parse_args()
    probe(arguments.port, arguments.keepalive_seconds)
