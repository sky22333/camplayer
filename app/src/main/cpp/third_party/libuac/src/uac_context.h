// Copyright 2023 Jakub Księżniak
// Patched for Android AUSBC libusb100.
//
// Licensed under the Apache License, Version 2.0

#pragma once

#include "libuac.h"
#include <thread>
#include <atomic>

namespace uac {

    class uac_context_impl : public uac_context, public std::enable_shared_from_this<uac_context> {
    public:
        explicit uac_context_impl(libusb_context *libusb_ctx);
        ~uac_context_impl() override;

        std::vector<std::shared_ptr<uac_device>> query_all_devices() override;
        std::shared_ptr<uac_device_handle> wrap(int fd) override;
        std::shared_ptr<uac_device_handle> wrap_android(
                int vid, int pid, int busnum, int devaddr, int fd, const char *usbfs) override;

    private:
        void ensure_event_thread();

        libusb_context *libusb_ctx;
        std::unique_ptr<std::thread> thread;
        std::atomic<bool> alive;
        bool owns_context;
    };

}
