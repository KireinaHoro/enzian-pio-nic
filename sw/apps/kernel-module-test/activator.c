#include<stdio.h>
#include<stdlib.h>
#include<string.h>
#include<sys/types.h>
#include<sys/stat.h>
#include<fcntl.h>
#include<unistd.h>
#include<sys/ioctl.h>
 
#define IOCTL_TEST_ACTIVATE_PID _IOW('t', 'a', pid_t)
 
int main() {
  int fd;
  // int32_t value, number;
  
  printf("Opening device\n");
  fd = open("/dev/pionic", O_RDWR);
  if (fd < 0) {
    printf("Cannot open device file...\n");
    return 0;
  }

  pid_t pid = 42;
  ioctl(fd, IOCTL_TEST_ACTIVATE_PID, &pid); 

  printf("Closing device\n");
  close(fd);
}