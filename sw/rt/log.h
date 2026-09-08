#ifndef LAUBERHORN_RT_LOG
#define LAUBERHORN_RT_LOG

#define LOG(fmt, ...) printf("[lauberhorn rt] " fmt "\n", ##__VA_ARGS__)
#define PERROR(msg) perror("[lauberhorn rt] " msg)

#ifdef DEBUG
#define DEBUG(fmt...) printf("[lauberhorn rt:DEBUG] " fmt "\n", ##__VA_ARGS__)
#else
#define DEBUG(fmt...)
#endif

#endif