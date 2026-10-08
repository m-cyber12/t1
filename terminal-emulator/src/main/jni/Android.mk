LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE:= harness-exec
LOCAL_SRC_FILES:= harness-exec.c
LOCAL_LDLIBS:= -ldl
include $(BUILD_SHARED_LIBRARY)
