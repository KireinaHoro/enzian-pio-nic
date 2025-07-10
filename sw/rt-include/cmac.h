#ifndef __PIONIC_CMAC_H__
#define __PIONIC_CMAC_H__

#include "gen/cmac.h"
#include <stdbool.h>

int start_cmac(cmac_t *cmac, bool loopback);
void stop_cmac(cmac_t *cmac);

#endif // __PIONIC_CMAC_H__
