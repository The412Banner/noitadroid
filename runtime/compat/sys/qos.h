// Darwin QoS classes. Linux has none: the call succeeds and changes nothing (core placement on Android is done
// separately, see docs/ANDROID.zh.md).
#pragma once

typedef enum {
    QOS_CLASS_UNSPECIFIED = 0x00,
    QOS_CLASS_BACKGROUND = 0x09,
    QOS_CLASS_UTILITY = 0x11,
    QOS_CLASS_DEFAULT = 0x15,
    QOS_CLASS_USER_INITIATED = 0x19,
    QOS_CLASS_USER_INTERACTIVE = 0x21,
} qos_class_t;

static inline int pthread_set_qos_class_self_np(qos_class_t qos, int relative_priority) {
    (void)qos, (void)relative_priority;
    return 0;
}
