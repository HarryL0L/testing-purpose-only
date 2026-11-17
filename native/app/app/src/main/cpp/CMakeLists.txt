cmake_minimum_required(VERSION 3.18)
project(nviewer CXX)
set(CMAKE_CXX_STANDARD 17)
add_library(nviewer SHARED nviewer.cpp)
# The display protocol is ncomposer's (native/gfx/nviewer.h).
target_include_directories(nviewer PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../gfx)
target_compile_options(nviewer PRIVATE -Wall -Wextra -Werror -Wno-unused-parameter)
target_link_libraries(nviewer EGL GLESv2 android log)
