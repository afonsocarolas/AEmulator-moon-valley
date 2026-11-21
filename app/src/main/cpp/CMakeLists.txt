# Added for AEmulator Moon Valley, 2026-11-21: VR mode on Meta Quest (OpenXR). GPL-3.0; see LICENSE and NOTICE.md.
cmake_minimum_required(VERSION 3.22.1)
project(aemuvr CXX)

find_package(OpenXR REQUIRED CONFIG)

add_library(aemuvr SHARED vr.cpp)
target_compile_options(aemuvr PRIVATE -Wall -O2 -fno-exceptions -fno-rtti)
target_link_libraries(aemuvr OpenXR::openxr_loader EGL GLESv3 android log)
