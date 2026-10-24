LOCAL_PATH:= $(call my-dir)

# Google Play requires 16 KB page support for apps targeting API 35+ (the
# requirement becomes blocking for updates on 2027-02-01). NDK r28 does this by
# default; this project pins r27, so the segment alignment is requested here for
# every shared object this module builds.
PAGE_ALIGNMENT := -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384

include $(CLEAR_VARS)
LOCAL_MODULE:= libtermux
LOCAL_SRC_FILES:= termux.c
LOCAL_LDFLAGS:= $(PAGE_ALIGNMENT)
include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE:= harness-exec
LOCAL_SRC_FILES:= harness-exec.c
LOCAL_LDLIBS:= -ldl
LOCAL_LDFLAGS:= $(PAGE_ALIGNMENT)
include $(BUILD_SHARED_LIBRARY)
