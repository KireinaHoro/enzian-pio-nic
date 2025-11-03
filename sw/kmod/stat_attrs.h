#ifndef LAUBERHORN_STAT_ATTRS_H
#define LAUBERHORN_STAT_ATTRS_H

#define STAT_ATTR_READ(class, _name)                                          \
	static ssize_t class##_##_name##_show(                                \
		struct device *dev, struct device_attribute *attr, char *buf) \
	{                                                                     \
		struct netdev_priv *priv = netdev_priv(to_net_dev(dev));      \
		return lauberhorn_eci_##class##_stat_##_name##_rd(            \
			&priv->class##_dev);                                  \
	}                                                                     \
	static struct device_attribute dev_attr_##class##_##_name = {         \
		.attr = {                                                     \
			.name = #_name,                                       \
			.mode = 0444,                                         \
		},                                                            \
		.show = class##_##_name##_show,                               \
	};

#define STAT_ATTR_ITEM(class, name) &dev_attr_##class##_##name.attr,

#define MAKE_STAT_GROUP(group_name, list_stats)                    \
	list_stats(STAT_ATTR_READ);                                \
	static struct attribute *group_name##_attrs[] = {          \
		list_stats(STAT_ATTR_ITEM) NULL,                   \
	};                                                         \
	static const struct attribute_group group_name##_group = { \
		.name = #group_name,                               \
		.attrs = group_name##_attrs,                       \
	};

#define STAT_GROUP_ITEM(group_name, list_stats) &group_name##_group,

#define MAKE_STAT_GROUPS(groups_name, list_groups)                           \
	list_groups(MAKE_STAT_GROUP);                                        \
	static const struct attribute_group *groups_name##_attr_groups[] = { \
		list_groups(STAT_GROUP_ITEM) NULL,                           \
	};

#endif // LAUBERHORN_STAT_ATTRS_H