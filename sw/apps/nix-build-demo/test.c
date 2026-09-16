#include <lauberhorn.h>
#include <stdio.h>

int main(void) {
    lauberhorn_t context;
    if (lauberhorn_init(&context)) {
        perror("lauberhorn_init");
        return 1;
    }
    lauberhorn_fini(&context);
    return 0;
}
