#include<stdio.h>
#include<stdlib.h>
#include<string.h>
#include<sys/types.h>
#include<sys/stat.h>
#include<fcntl.h>
#include<unistd.h>
#include<sys/ioctl.h>
 
#define IOCTL_TEST_ACTIVATE_PID _IOW('t', 'a', pid_t)
 
int main(int argc, const char* argv[], const char* envp[]) {
  int fd;
  // int32_t value, number;
  
//   printf("Opening device\n");
  fd = open("/dev/pionic", O_RDWR);
  if (fd < 0) {
    printf("Cannot open device file...\n");
    return -1;
  }

  pid_t pid = -1;
  if (argc != 2 || (sscanf(argv[1], "%i", &pid) != 1)) {
    printf("usage: %s <pid>\n", argv[0]);
    return -1;
  }

  printf("activator: going to active %i...", pid);
  ioctl(fd, IOCTL_TEST_ACTIVATE_PID, &pid); 
  printf("Done\n");

//   printf("Closing device\n");
  close(fd);
}