cmake_minimum_required(VERSION 3.18)
project(cleanvoice C)

set(RN ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise)
if(NOT EXISTS ${RN}/src/rnnoise_data.c)
    message(FATAL_ERROR "RNNoise model missing. Run: ./gradlew :app:downloadRnnoiseModel")
endif()

add_library(cleanvoice SHARED
    rnnoise_jni.c
    ${RN}/src/denoise.c
    ${RN}/src/rnn.c
    ${RN}/src/pitch.c
    ${RN}/src/kiss_fft.c
    ${RN}/src/celt_lpc.c
    ${RN}/src/nnet.c
    ${RN}/src/nnet_default.c
    ${RN}/src/parse_lpcnet_weights.c
    ${RN}/src/rnnoise_data.c
    ${RN}/src/rnnoise_tables.c)

target_include_directories(cleanvoice PRIVATE ${RN}/include ${RN}/src)
target_compile_options(cleanvoice PRIVATE -O3 -fvisibility=hidden -Wno-unused-parameter)
target_link_libraries(cleanvoice m)
