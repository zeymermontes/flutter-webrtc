cmake_minimum_required(VERSION 3.18.1)
project(flutter_webrtc_log_filter C)

add_library(flutter_webrtc_log_filter SHARED native_log_filter.c)
target_link_libraries(flutter_webrtc_log_filter log dl)
# 16 KB pages (Android 15+).
target_link_options(flutter_webrtc_log_filter PRIVATE "-Wl,-z,max-page-size=16384")
