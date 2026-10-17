#!/usr/bin/env python3
"""Build fixed APK workers with the already provisioned SDK/NDK; no network."""
import argparse
from pathlib import Path
import subprocess
import shutil
from prepare import prepare

p = argparse.ArgumentParser()
p.add_argument('--sdk', required=True, type=Path)
p.add_argument('--output', required=True, type=Path)
a = p.parse_args()
root = Path(__file__).resolve().parent
out = a.output.resolve()
work = out.parent / 'work'
prepare(work / 'vendor')
cmake = a.sdk / 'cmake/3.22.1/bin/cmake'
ninja = a.sdk / 'cmake/3.22.1/bin/ninja'
ndk = a.sdk / 'ndk/28.2.13676358'
for abi in ['arm64-v8a', 'armeabi-v7a', 'x86_64']:
    build = work / abi
    subprocess.run([str(cmake), '-S', str(root), '-B', str(build), '-G', 'Ninja',
                    '-DCMAKE_BUILD_TYPE=Release', f'-DCMAKE_MAKE_PROGRAM={ninja}',
                    f'-DCMAKE_TOOLCHAIN_FILE={ndk}/build/cmake/android.toolchain.cmake',
                    '-DANDROID_PLATFORM=android-26', '-DANDROID_STL=c++_static',
                    f'-DANDROID_ABI={abi}', f'-DMYCT_VENDOR={work}/vendor'], check=True)
    subprocess.run([str(cmake), '--build', str(build), '-j', '2'], check=True)
    target = out / abi / 'libmyct-c-worker.so'
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(build / target.name, target)
    subprocess.run([str(ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'), str(target)], check=True)
