#include<stdio.h>
#include<stdlib.h>
#include<string.h>
#include<sys/types.h>
#include<sys/stat.h>
#include<fcntl.h>
#include<unistd.h>
#include<sys/ioctl.h>
 
#define IOCTL_YIELD _IO('p', 'y')
 
int main() {
  int fd;
  // int32_t value, number;
  
  printf("Opening device\n");
  fd = open("/dev/pionic", O_RDWR);
  if (fd < 0) {
    printf("Cannot open device file...\n");
    return 0;
  }

  ioctl(fd, IOCTL_YIELD); 

  printf("Closing device\n");
  close(fd);
}