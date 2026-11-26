# Derived from Shizuku (https://github.com/RikkaApps/Shizuku, Apache-2.0), modified for RiriProjector:
# only the pairing library is built (no starter executable). See LICENSE-Shizuku and ADR-014.
cmake_minimum_required(VERSION 3.22)

project("riri_adb")

set(CMAKE_CXX_STANDARD 17)

add_compile_options(-Werror=format -fdata-sections -ffunction-sections -fno-exceptions -fno-rtti -fno-threadsafe-statics)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-Os -fvisibility=hidden -fvisibility-inlines-hidden)
    add_link_options(-Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,--strip-all)
endif ()

find_package(boringssl REQUIRED CONFIG)
find_package(cxx REQUIRED CONFIG)

add_library(riri_adb SHARED adb_pairing.cpp)

target_link_libraries(riri_adb log boringssl::crypto_static cxx::cxx)
