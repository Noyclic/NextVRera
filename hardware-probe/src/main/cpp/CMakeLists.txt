cmake_minimum_required(VERSION 3.22.1)
project(nextvrera_hardware_probe LANGUAGES CXX)

add_library(hardwareprobe SHARED hardware_probe.cpp)
target_compile_features(hardwareprobe PRIVATE cxx_std_17)
target_compile_options(hardwareprobe PRIVATE -Wall -Wextra -Werror)

find_library(log-lib log)
target_link_libraries(hardwareprobe ${log-lib} dl)
