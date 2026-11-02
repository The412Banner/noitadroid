/* Python's command line, for the build on the device: python.org's Android build is a library only. The app sets
 * PYTHONHOME to the standard library it extracted; libpython3.14.so sits next to this program. */
#include <Python.h>

int main(int argc, char **argv) {
    return Py_BytesMain(argc, argv);
}
