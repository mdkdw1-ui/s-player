cmake_minimum_required(VERSION 3.22.1)
project(splayer)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# whisper.cpp 경로
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# whisper.cpp 를 서브디렉토리로 추가
add_subdirectory(${WHISPER_DIR} ${CMAKE_BINARY_DIR}/whisper)

# whisper JNI 라이브러리
add_library(splayer_whisper SHARED
    whisper_jni.cpp
)

target_link_libraries(splayer_whisper
    whisper
    android
    log
)

# 헤더 포함 경로
target_include_directories(splayer_whisper PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)
