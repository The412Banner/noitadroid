/* lld for clang's driver (--ld-path): runs libnd_llvm.so, next to this program in the app's native library
 * directory, as "ld.lld". The multicall binary picks its tool from that argument; clang's driver runs its linker
 * by path, and Android installs each program under one lib*.so name only. */
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char llvm[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", llvm, sizeof llvm - 1);
    if (n <= 0) {
        perror("nd_ld: /proc/self/exe");
        return 127;
    }
    llvm[n] = 0;
    char *slash = strrchr(llvm, '/');
    if (!slash || (size_t)(slash - llvm) + sizeof "/libnd_llvm.so" > sizeof llvm) return 127;
    strcpy(slash, "/libnd_llvm.so");

    char **args = calloc((size_t)argc + 2, sizeof *args);
    if (!args) return 127;
    args[0] = llvm;
    args[1] = "ld.lld";
    for (int i = 1; i < argc; i++) args[i + 1] = argv[i];
    execv(llvm, args);
    perror(llvm);
    return 127;
}
