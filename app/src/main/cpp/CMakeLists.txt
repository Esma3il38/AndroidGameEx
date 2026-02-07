cmake_minimum_required(VERSION 3.18.1)
project("gameex_native")

add_library(native-scanner SHARED memory_scanner.cpp lua_disassembler.cpp speedhack.cpp)
find_library(log-lib log)
target_link_libraries(native-scanner ${log-lib})
