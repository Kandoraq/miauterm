#define _GNU_SOURCE
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <netinet/in.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/utsname.h>

static int (*next_bind)(int, const struct sockaddr *, socklen_t);
static int (*next_connect)(int, const struct sockaddr *, socklen_t);
static int (*next_gethostname)(char *, size_t);
static int (*next_uname)(struct utsname *);

__attribute__((constructor)) static void resolve_guest_functions(void) {
    next_bind = dlsym(RTLD_NEXT, "bind");
    next_connect = dlsym(RTLD_NEXT, "connect");
    next_gethostname = dlsym(RTLD_NEXT, "gethostname");
    next_uname = dlsym(RTLD_NEXT, "uname");
}

static const char *configured_hostname(void) {
    const char *hostname = getenv("MIAUTERM_HOSTNAME");
    return hostname && hostname[0] ? hostname : NULL;
}

static int is_redirect_port(uint16_t port) {
    return port == 80 || port == 443;
}

static uint16_t redirected_port(uint16_t port) {
    if (port == 80) return 2080;
    if (port == 443) return 2443;
    return port;
}

static int is_loopback(const struct sockaddr *address) {
    if (address->sa_family == AF_INET) {
        const struct sockaddr_in *ipv4 = (const struct sockaddr_in *)address;
        return (ntohl(ipv4->sin_addr.s_addr) >> 24) == 127;
    }
    if (address->sa_family == AF_INET6) {
        const struct sockaddr_in6 *ipv6 = (const struct sockaddr_in6 *)address;
        return IN6_IS_ADDR_LOOPBACK(&ipv6->sin6_addr);
    }
    return 0;
}

static int copy_with_redirect(
    const struct sockaddr *address,
    socklen_t length,
    int loopback_only,
    struct sockaddr_storage *storage,
    const struct sockaddr **rewritten
) {
    if (!address || length > sizeof(*storage) || length < sizeof(sa_family_t)) return 0;
    if (loopback_only && !is_loopback(address)) return 0;

    uint16_t *port = NULL;
    if (address->sa_family == AF_INET && length >= sizeof(struct sockaddr_in)) {
        port = (uint16_t *)&((struct sockaddr_in *)address)->sin_port;
    } else if (address->sa_family == AF_INET6 && length >= sizeof(struct sockaddr_in6)) {
        port = (uint16_t *)&((struct sockaddr_in6 *)address)->sin6_port;
    } else {
        return 0;
    }

    const uint16_t host_port = ntohs(*port);
    if (!is_redirect_port(host_port)) return 0;

    __builtin_memcpy(storage, address, length);
    if (address->sa_family == AF_INET) {
        ((struct sockaddr_in *)storage)->sin_port = htons(redirected_port(host_port));
    } else {
        ((struct sockaddr_in6 *)storage)->sin6_port = htons(redirected_port(host_port));
    }
    *rewritten = (const struct sockaddr *)storage;
    return 1;
}

int bind(int fd, const struct sockaddr *address, socklen_t length) {
    if (!next_bind) {
        errno = ENOSYS;
        return -1;
    }
    struct sockaddr_storage storage;
    const struct sockaddr *effective = address;
    copy_with_redirect(address, length, 0, &storage, &effective);
    return next_bind(fd, effective, length);
}

int connect(int fd, const struct sockaddr *address, socklen_t length) {
    if (!next_connect) {
        errno = ENOSYS;
        return -1;
    }
    struct sockaddr_storage storage;
    const struct sockaddr *effective = address;
    copy_with_redirect(address, length, 1, &storage, &effective);
    return next_connect(fd, effective, length);
}

int gethostname(char *name, size_t length) {
    const char *hostname = configured_hostname();
    if (!hostname) {
        if (!next_gethostname) {
            errno = ENOSYS;
            return -1;
        }
        return next_gethostname(name, length);
    }
    if (!name || length == 0 || strnlen(hostname, length) >= length) {
        errno = ENAMETOOLONG;
        return -1;
    }
    const size_t hostname_length = strlen(hostname);
    memcpy(name, hostname, hostname_length + 1);
    return 0;
}

int uname(struct utsname *name) {
    if (!next_uname) {
        errno = ENOSYS;
        return -1;
    }
    const int result = next_uname(name);
    if (result != 0 || !name) return result;
    const char *hostname = configured_hostname();
    if (hostname) {
        const size_t limit = sizeof(name->nodename) - 1;
        const size_t count = strnlen(hostname, limit);
        memcpy(name->nodename, hostname, count);
        name->nodename[count] = '\0';
    }
    return result;
}
