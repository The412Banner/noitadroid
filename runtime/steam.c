// Steamworks (steam_api.dll) stubbed natively (HLE) for the Steam build of noita.exe: the game always runs offline,
// as it does on Windows without Steam. SteamAPI_Init fails, so the game logs "Init failed" and keeps the flag that
// guards its Steam calls (0x1206fa8) clear: it never asks to be relaunched by Steam (SteamAPI_RestartAppIfNecessary
// comes after a successful init) and skips achievements, stats and the Workshop.
//
// SteamInternal_ContextInit(data) is what each SteamUser()-style accessor calls: data is {init function, counter,
// interface pointer}, and the result points at the interface pointer, which the game checks for NULL before use.
// Without Steam the init function would set it from SteamInternal_FindOrCreateUserInterface, which gives NULL, so
// it isn't called: the pointer stays as the image has it, NULL.
#include "host.h"

HOST_CDECL(steam_api, SteamAPI_Init) { ret_i32(c, 0); }
HOST_CDECL(steam_api, SteamAPI_RestartAppIfNecessary) { ret_i32(c, 0); }
HOST_CDECL(steam_api, SteamAPI_RunCallbacks) {}
HOST_CDECL(steam_api, SteamAPI_RegisterCallback) {}
HOST_CDECL(steam_api, SteamAPI_UnregisterCallback) {}
HOST_CDECL(steam_api, SteamAPI_Shutdown) {}
HOST_CDECL(steam_api, SteamAPI_GetHSteamUser) { ret_i32(c, 0); }
HOST_CDECL(steam_api, SteamInternal_FindOrCreateUserInterface) { ret_i32(c, 0); }
HOST_CDECL(steam_api, SteamInternal_ContextInit) { ret_i32(c, ARG(0) + 8); }
