cmake_minimum_required(VERSION 3.22.1)
project(UACAudio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# NDK r28+ defaults to 16KB ELF alignment for 64-bit; keep explicit for clarity.
if (ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    add_link_options("-Wl,-z,max-page-size=16384")
endif()

set(LIBUAC_DIR ${CMAKE_SOURCE_DIR}/third_party/libuac)
set(LIBUSB_INCLUDE ${CMAKE_SOURCE_DIR}/third_party/libusb/include)
set(PREBUILT_DIR ${CMAKE_SOURCE_DIR}/prebuilt/${ANDROID_ABI})

add_library(usb100 SHARED IMPORTED)
set_target_properties(usb100 PROPERTIES
        IMPORTED_LOCATION ${PREBUILT_DIR}/libusb100.so)

set(LIBUAC_SOURCES
        ${LIBUAC_DIR}/src/uac_context.cpp
        ${LIBUAC_DIR}/src/uac_device.cpp
        ${LIBUAC_DIR}/src/uac_parser.cpp
        ${LIBUAC_DIR}/src/uac_streaming.cpp
        ${LIBUAC_DIR}/src/uac_exceptions.cpp
)

add_library(UACAudio SHARED
        uac_jni.cpp
        ${LIBUAC_SOURCES}
)

target_include_directories(UACAudio PRIVATE
        ${LIBUAC_DIR}/include
        ${LIBUAC_DIR}/src
        ${LIBUSB_INCLUDE}
        ${LIBUSB_INCLUDE}/libusb-1.0
)

target_compile_definitions(UACAudio PRIVATE
        UAC_ENABLE_LOGGING
)

target_compile_options(UACAudio PRIVATE
        -fno-rtti
        -fexceptions
        -Wall
)

find_library(log-lib log)

target_link_libraries(UACAudio
        usb100
        ${log-lib}
)
