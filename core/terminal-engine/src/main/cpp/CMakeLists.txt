cmake_minimum_required(VERSION 3.22.1)

project("miauterm" LANGUAGES C)

# Main PTY JNI bridge & native tar extractor, with the Zstandard decoder for the bootstrap archive
add_library(
    miauterm-pty
    SHARED
    miauterm-pty.c
    zstd/zstd_decoder.c
)

find_library(
    log-lib
    log
)

target_link_libraries(
    miauterm-pty
    ${log-lib}
)

target_link_options(
    miauterm-pty
    PRIVATE
    "-Wl,-z,max-page-size=16384"
)

