cmake_minimum_required(VERSION 3.22.1)
project(everypods_relay C)
include(FetchContent)

# libopus 1.5.2 (BSD-3-Clause). Pinned to the official xiph.org tarball; SHA-256 matches
# https://downloads.xiph.org/releases/opus/SHA256SUMS.txt
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
FetchContent_Declare(opus
    URL https://downloads.xiph.org/releases/opus/opus-1.5.2.tar.gz
    URL_HASH SHA256=65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1)
FetchContent_MakeAvailable(opus)

add_library(everypods_opus SHARED opus_jni.c)
target_link_libraries(everypods_opus PRIVATE opus log)
target_link_options(everypods_opus PRIVATE "-Wl,-z,max-page-size=16384")
