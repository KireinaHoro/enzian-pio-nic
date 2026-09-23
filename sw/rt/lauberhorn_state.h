// Shared macros and runtime globals for rt/*.c files.
// Not part of the public API.

#ifndef LAUBERHORN_RT_INTERNAL_H
#define LAUBERHORN_RT_INTERNAL_H

#include <signal.h>
#include <stdio.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

#include "lauberhorn.h"
#include "srv_table.h"

extern struct pending_call_table pending_call_table;

extern volatile sig_atomic_t lauberhorn_is_running;
extern int lauberhorn_fd;


#endif // LAUBERHORN_RT_INTERNAL_H
