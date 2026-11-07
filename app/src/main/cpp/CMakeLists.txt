cmake_minimum_required(VERSION 3.22.1)
project("microscope_cv")

find_package(OpenMP REQUIRED)

add_library(
    microscope_cv
    SHARED
    native_microscope_cv.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    microscope_cv
    ${log-lib}
    ${jnigraphics-lib}
    OpenMP::OpenMP_CXX
)
