#pragma once
#include <stddef.h>
#include <stdint.h>

#define BLAKE2S_BLOCKBYTES     64
#define BLAKE2S_OUTBYTES       32
#define BLAKE2S_KEYBYTES       32
#define BLAKE2S_SALTBYTES       8
#define BLAKE2S_PERSONALBYTES   8

typedef struct {
    uint32_t h[8];
    uint32_t t[2];
    uint32_t f[2];
    uint8_t  buf[BLAKE2S_BLOCKBYTES];
    size_t   buflen;
    size_t   outlen;
    uint8_t  last_node;
} blake2s_state;

int blake2s_init   (blake2s_state *S, size_t outlen);
int blake2s_update (blake2s_state *S, const void *in, size_t inlen);
int blake2s_final  (blake2s_state *S, void *out, size_t outlen);
int blake2s        (void *out, size_t outlen, const void *in, size_t inlen);
