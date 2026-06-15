#pragma once
/* Arcanum stub: pull CRYPTOPP_ALIGN_DATA and CRYPTOPP_BOOL_* from config.h. */
#include "../Crypto/config.h"
/* NDK clang does not recognize the MS-style __int64 keyword.
   Use long: on arm64/x86-64 LP64, long == uint64_t (unsigned long),
   so typedef redefinitions in Tcdefs.h and Xts.h resolve to the same type. */
#ifndef __int64
#  define __int64 long
#endif
