#!/bin/bash
# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-License-Identifier: Apache-2.0
set -ex

# Install the EFA userspace stack on the supported container bases.
. /etc/os-release
case "${ID}:${VERSION_ID}" in
    ubuntu:*) EFA_LIB_DIR=/opt/amazon/efa/lib ;;
    rocky:8*) EFA_LIB_DIR=/opt/amazon/efa/lib64 ;;
    *)
        echo "Skipping EFA installer: OS '${ID}' version '${VERSION_ID}'."
        exit 0
        ;;
esac

EFA_INSTALLER_VERSION="${EFA_INSTALLER_VERSION:-1.51.0}"
# TODO: Use an internal mirror once the pinned EFA installer is available there.
EFA_INSTALLER_URL="https://efa-installer.amazonaws.com/aws-efa-installer-${EFA_INSTALLER_VERSION}.tar.gz"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "${WORK_DIR}"' EXIT
cd "${WORK_DIR}"

curl --connect-timeout 30 --max-time 1800 --retry 3 --retry-delay 2 \
    -fsSL -o "aws-efa-installer-${EFA_INSTALLER_VERSION}.tar.gz" \
    "${EFA_INSTALLER_URL}"
tar -xf "aws-efa-installer-${EFA_INSTALLER_VERSION}.tar.gz"
cd aws-efa-installer

# Preserve the base image's MPI and rdma-core.
if [ "${ID}" = ubuntu ]; then
    apt-get update
    # --build-ngc handles userspace-only installation for Ubuntu containers.
    ./efa_installer.sh --skip-mpi --skip-rdma-core --build-ngc
else
    # Rocky uses standard RPMs; skip host setup and the unrelated NCCL plugin.
    # The installer selects top-level DKMS RPMs even with --skip-kmod.
    rm -f "RPMS/ROCKYLINUX8/$(uname -m)"/dkms-*.rpm
    ./efa_installer.sh --yes --skip-kmod --skip-limit-conf --no-verify \
        --skip-mpi --skip-rdma-core --skip-plugin --disable-ngc
fi

# Prefer the EFA runtime over an older distribution libfabric in the loader cache.
printf '%s\n' "${EFA_LIB_DIR}" > /etc/ld.so.conf.d/000_efa.conf
ldconfig
