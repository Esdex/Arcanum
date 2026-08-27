/*---------------------------------------------------------------------------/
/  FatFs configuration for Arcanum (R0.16)
/---------------------------------------------------------------------------*/
#define FFCONF_DEF  80386

#define FF_FS_READONLY  0
#define FF_FS_MINIMIZE  0
#define FF_USE_FIND     0
#define FF_USE_MKFS     1
#define FF_USE_FASTSEEK 1
#define FF_USE_EXPAND   0
#define FF_USE_CHMOD    1   /* brings in f_utime, used to keep a file's own date (#154) */
#define FF_USE_LABEL    1
#define FF_USE_FORWARD  0
#define FF_USE_STRFUNC  0
#define FF_PRINT_LLI    0
#define FF_PRINT_FLOAT  0
#define FF_STRF_ENCODE  0

#define FF_CODE_PAGE    437
#define FF_USE_LFN      1
#define FF_MAX_LFN      255
#define FF_LFN_UNICODE  2
#define FF_LFN_BUF      255
#define FF_SFN_BUF      12
#define FF_FS_RPATH     0
#define FF_PATH_DEPTH   10

#define FF_VOLUMES      4
#define FF_STR_VOLUME_ID 0
#define FF_VOLUME_STRS  "0","1","2","3"
#define FF_MULTI_PARTITION 0
#define FF_MIN_SS       512
#define FF_MAX_SS       512
#define FF_LBA64        0
#define FF_MIN_GPT      0x10000000
#define FF_USE_TRIM     0

#define FF_FS_TINY      0
#define FF_FS_EXFAT     1
/* With FF_FS_NORTC every file written was stamped with the constant below - one fixed
/  date for every file ever imported (#154). get_fattime() in diskio.cpp reads the clock
/  instead. The FF_NORTC_* values are left here because they still bound a build that
/  turns the clock back off, not because they are used. */
#define FF_FS_NORTC     0
#define FF_NORTC_MON    1
#define FF_NORTC_MDAY   1
#define FF_NORTC_YEAR   2025
#define FF_FS_CRTIME    0
#define FF_FS_NOFSINFO  0
#define FF_FS_LOCK      0
#define FF_FS_REENTRANT 0
#define FF_FS_TIMEOUT   1000
