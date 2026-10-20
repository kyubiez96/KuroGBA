cmake_minimum_required(VERSION 3.22)
project(kurogba_host C)

# Small libretro host library loaded by the Kotlin app via JNI.
# It dlopen()s libmgba_libretro.so at runtime — the core itself is built
# from third_party/mgba by tools/build-native.sh, not here.
add_library(kurogba SHARED kurogba_host.c)
target_compile_options(kurogba PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
target_link_libraries(kurogba dl log)
