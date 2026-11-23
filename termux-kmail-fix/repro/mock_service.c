/* Minimal Secret Service that behaves like ksecretd for collection creation:
 * CreateCollection makes the collection, emits CollectionCreated and
 * PropertiesChanged(Collections), and uniquifies the wallet name when the
 * label is already taken (kdewallet -> kdewallet__0_). */
#include <gio/gio.h>
#include <stdio.h>
#include <string.h>

#define ROOT "/org/freedesktop/secrets"
static const char xml[] =
"<node>"
" <interface name='org.freedesktop.Secret.Service'>"
"  <method name='CreateCollection'><arg type='a{sv}' direction='in'/><arg type='s' direction='in'/>"
"   <arg type='o' direction='out'/><arg type='o' direction='out'/></method>"
"  <signal name='CollectionCreated'><arg type='o'/></signal>"
"  <property name='Collections' type='ao' access='read'/>"
" </interface>"
" <interface name='org.freedesktop.Secret.Collection'>"
"  <property name='Items' type='ao' access='read'/>"
"  <property name='Label' type='s' access='read'/>"
"  <property name='Locked' type='b' access='read'/>"
"  <property name='Created' type='t' access='read'/>"
"  <property name='Modified' type='t' access='read'/>"
" </interface>"
"</node>";

static GDBusNodeInfo *info;
static GPtrArray *paths, *names, *labels;

static GVariant *collections_variant(void)
{
    GVariantBuilder b; g_variant_builder_init(&b, G_VARIANT_TYPE("ao"));
    for (guint i = 0; i < paths->len; i++) g_variant_builder_add(&b, "o", (char *)paths->pdata[i]);
    return g_variant_builder_end(&b);
}

static GVariant *col_get(GDBusConnection *c, const gchar *s, const gchar *path, const gchar *i, const gchar *prop, GError **e, gpointer label)
{
    if (!strcmp(prop, "Items")) return g_variant_new("ao", NULL);
    if (!strcmp(prop, "Label")) return g_variant_new_string((const char *)label);
    if (!strcmp(prop, "Locked")) return g_variant_new_boolean(FALSE);
    return g_variant_new_uint64(0);
}
static const GDBusInterfaceVTable col_vt = { NULL, col_get, NULL, {0} };

static gboolean name_taken(const char *n)
{
    for (guint i = 0; i < names->len; i++) if (!strcmp(names->pdata[i], n)) return TRUE;
    return FALSE;
}

static void svc_call(GDBusConnection *conn, const gchar *sender, const gchar *path, const gchar *iface, const gchar *method,
                     GVariant *params, GDBusMethodInvocation *inv, gpointer ud)
{
    GVariant *props; const gchar *alias, *label = NULL;
    g_variant_get(params, "(@a{sv}&s)", &props, &alias);
    g_variant_lookup(props, "org.freedesktop.Secret.Collection.Label", "&s", &label);
    char *name = g_strdup(label); int n = -1;
    while (name_taken(name)) { g_free(name); name = g_strdup_printf("%s__%d_", label, ++n); }
    char *cpath = g_strdup_printf(ROOT "/collection/%s", name);
    g_ptr_array_add(names, name); g_ptr_array_add(paths, cpath);
    char *lab = g_strdup(label); g_ptr_array_add(labels, lab);
    g_dbus_connection_register_object(conn, cpath, info->interfaces[1], &col_vt, lab, NULL, NULL);
    printf("MOCK: created wallet '%s' (requested label '%s')\n", name, label); fflush(stdout);
    g_dbus_connection_emit_signal(conn, NULL, ROOT, "org.freedesktop.Secret.Service", "CollectionCreated", g_variant_new("(o)", cpath), NULL);
    GVariantBuilder ch; g_variant_builder_init(&ch, G_VARIANT_TYPE("a{sv}"));
    g_variant_builder_add(&ch, "{sv}", "Collections", collections_variant());
    g_dbus_connection_emit_signal(conn, NULL, ROOT, "org.freedesktop.DBus.Properties", "PropertiesChanged",
                                  g_variant_new("(sa{sv}as)", "org.freedesktop.Secret.Service", &ch, NULL), NULL);
    g_dbus_method_invocation_return_value(inv, g_variant_new("(oo)", cpath, "/"));
    g_variant_unref(props);
}
static GVariant *svc_get(GDBusConnection *c, const gchar *s, const gchar *p, const gchar *i, const gchar *prop, GError **e, gpointer ud)
{
    return collections_variant();
}
static const GDBusInterfaceVTable svc_vt = { svc_call, svc_get, NULL, {0} };

static void on_bus(GDBusConnection *conn, const gchar *name, gpointer ud)
{
    g_dbus_connection_register_object(conn, ROOT, info->interfaces[0], &svc_vt, NULL, NULL, NULL);
}
static void on_name(GDBusConnection *conn, const gchar *name, gpointer ud) { printf("MOCK: ready\n"); fflush(stdout); }

int main(void)
{
    info = g_dbus_node_info_new_for_xml(xml, NULL);
    paths = g_ptr_array_new(); names = g_ptr_array_new(); labels = g_ptr_array_new();
    g_bus_own_name(G_BUS_TYPE_SESSION, "org.freedesktop.secrets", G_BUS_NAME_OWNER_FLAGS_NONE, on_bus, on_name, NULL, NULL, NULL);
    g_main_loop_run(g_main_loop_new(NULL, FALSE));
    return 0;
}
