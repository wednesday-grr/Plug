cmake_minimum_required(VERSION 3.22.1)
project(gramophone_essentia LANGUAGES CXX)

set(ESSENTIA_DIR "${ESSENTIA_ROOT}/${ANDROID_ABI}")
foreach(required_file lib/libessentia.a build-info.txt licenses/essentia-COPYING.txt licenses/eigen-copyright)
    if (NOT EXISTS "${ESSENTIA_DIR}/${required_file}")
        message(FATAL_ERROR "Essentia artifact missing ${required_file} for ${ANDROID_ABI}: ${ESSENTIA_DIR}")
    endif()
endforeach()
file(READ "${ESSENTIA_DIR}/build-info.txt" BUILD_INFO)
foreach(expected
    "Essentia commit: df27f949d7827a43b4afc216fd5c788454b0f8a4"
    "NDK: 27.2.12479018"
    "API: 23"
    "ABI: ${ANDROID_ABI}"
    "Eigen package: 3.4.0-4build0.1")
    string(FIND "${BUILD_INFO}" "${expected}" found)
    if (found EQUAL -1)
        message(FATAL_ERROR "Essentia ${ANDROID_ABI} build-info.txt does not contain: ${expected}")
    endif()
endforeach()

add_library(essentia STATIC IMPORTED)
set_target_properties(essentia PROPERTIES IMPORTED_LOCATION "${ESSENTIA_DIR}/lib/libessentia.a")
add_library(gramophone_essentia SHARED essentia_jni.cpp)
target_compile_features(gramophone_essentia PRIVATE cxx_std_14)
target_include_directories(gramophone_essentia PRIVATE
    "${ESSENTIA_DIR}/include"
    "${ESSENTIA_DIR}/include/essentia"
    "${ESSENTIA_DIR}/include/eigen3")
target_link_libraries(gramophone_essentia PRIVATE essentia atomic log)
target_link_options(gramophone_essentia PRIVATE
        "-Wl,--no-undefined"
        "-Wl,-z,max-page-size=16384"
)

