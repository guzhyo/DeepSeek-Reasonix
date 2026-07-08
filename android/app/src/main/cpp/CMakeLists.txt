cmake_minimum_required(VERSION 3.18.1)
project("ptyjni")

add_library(ptyjni SHARED jni_pty.c)

target_include_directories(ptyjni PRIVATE
    ${CMAKE_SOURCE_DIR}/../java/com/nousresearch/reasonix
)

find_library(log-lib log)
target_link_libraries(ptyjni ${log-lib})
