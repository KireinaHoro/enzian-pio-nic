static dev_t dev = 0;
static struct cdev cdev;
static struct class *dev_class;

/**
 * Create character devices for control-path functions towards userspace.
 * Two devices will be created:
 *  - /dev/lauberhorn: accessible to normal user, app access
 *  - /dev/lauberhorn_mgmt: lower-level access, available to superuser
 */
int create_devices(void) {
  if (alloc_chrdev_region(&dev, 0, 1, "lauberhorn") < 0) {
    pr_err("alloc_chrdev_region failed\n");
    return -1;
  }
  pr_info("chrdev major = %d, minor = %d \n", MAJOR(dev), MINOR(dev));
  cdev_init(&cdev, &fops);
  if (cdev_add(&cdev, dev, 1) < 0) {
    pr_err("cdev_add failed\n");
    return -1;
  }
  cdev.owner = THIS_MODULE;
  if (IS_ERR(dev_class = class_create("lauberhorn_class"))) {
    pr_err("class_create failed\n");
    return -1;
  }
  if (IS_ERR(device_create(dev_class, NULL, dev, NULL, "lauberhorn"))) {
    pr_err("device_create failed\n");
    return -1;
  }
  pr_info("Device created at /dev/lauberhorn\n");
  return 0;
}

void remove_devices(void) {
  device_destroy(dev_class, dev);
  class_destroy(dev_class);
  cdev_del(&cdev);
  unregister_chrdev_region(dev, 1);
  pr_info("Device removed\n");
}