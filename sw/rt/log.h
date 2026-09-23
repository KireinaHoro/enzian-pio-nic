#ifndef LAUBERHORN_RT_LOG
#define LAUBERHORN_RT_LOG

#define LOG(fmt, ...) printf("[lauberhorn rt] " fmt "\n", ##__VA_ARGS__)
#define PERROR(msg) perror("[lauberhorn rt] " msg)

/* DEBUG is a build flag used by the shared ECI core; never define it here. */

#endif
