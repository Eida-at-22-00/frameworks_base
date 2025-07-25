/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "androidfw/AssetsProvider.h"

#include <sys/stat.h>

#include <android-base/errors.h>
#include <android-base/stringprintf.h>
#include <android-base/utf8.h>
#include <ziparchive/zip_archive.h>

namespace android {

static constexpr std::string_view kEmptyDebugString = "<empty>";

std::unique_ptr<AssetsProvider> AssetsProvider::CreateWithOverride(
    std::unique_ptr<AssetsProvider> provider, std::unique_ptr<AssetsProvider> override) {
  if (provider == nullptr) {
    return {};
  }
  if (override == nullptr) {
    return provider;
  }
  return MultiAssetsProvider::Create(std::move(override), std::move(provider));
}

std::unique_ptr<AssetsProvider> AssetsProvider::CreateFromNullable(
    std::unique_ptr<AssetsProvider> nullable) {
  if (nullable) {
    return nullable;
  }
  return EmptyAssetsProvider::Create();
}

std::unique_ptr<Asset> AssetsProvider::Open(const std::string& path, Asset::AccessMode mode,
                                            bool* file_exists) const {
  return OpenInternal(path, mode, file_exists);
}

std::unique_ptr<Asset> AssetsProvider::CreateAssetFromFile(const std::string& path) {
  base::unique_fd fd(base::utf8::open(path.c_str(), O_RDONLY | O_CLOEXEC));
  if (!fd.ok()) {
    LOG(ERROR) << "Failed to open file '" << path << "': " << base::SystemErrorCodeToString(errno);
    return {};
  }

  return CreateAssetFromFd(std::move(fd), path.c_str());
}

std::unique_ptr<Asset> AssetsProvider::CreateAssetFromFd(base::unique_fd fd,
                                                         const char* path,
                                                         off64_t offset,
                                                         off64_t length) {
  CHECK(length >= kUnknownLength) << "length must be greater than or equal to " << kUnknownLength;
  CHECK(length != kUnknownLength || offset == 0) << "offset must be 0 if length is "
                                                 << kUnknownLength;
  if (length == kUnknownLength) {
    length = lseek64(fd, 0, SEEK_END);
    if (length < 0) {
      LOG(ERROR) << "Failed to get size of file '" << ((path) ? path : "anon") << "': "
                 << base::SystemErrorCodeToString(errno);
      return {};
    }
  }

  incfs::IncFsFileMap file_map;
  if (!file_map.Create(fd, offset, static_cast<size_t>(length), path)) {
    LOG(ERROR) << "Failed to mmap file '" << ((path != nullptr) ? path : "anon") << "': "
               << base::SystemErrorCodeToString(errno);
    return {};
  }

  // If `path` is set, do not pass ownership of the `fd` to the new Asset since
  // Asset::openFileDescriptor can use `path` to create new file descriptors.
  return Asset::createFromUncompressedMap(std::move(file_map),
                                          Asset::AccessMode::ACCESS_RANDOM,
                                          (path != nullptr) ? base::unique_fd(-1) : std::move(fd));
}

const std::string* ZipAssetsProvider::PathOrDebugName::GetPath() const {
  return is_path_ ? &value_ : nullptr;
}

const std::string& ZipAssetsProvider::PathOrDebugName::GetDebugName() const {
  return value_;
}

void ZipAssetsProvider::ZipCloser::operator()(ZipArchive* a) const {
  ::CloseArchive(a);
}

ZipAssetsProvider::ZipAssetsProvider(ZipArchiveHandle handle, PathOrDebugName&& path,
                                     package_property_t flags, time_t last_mod_time)
    : zip_handle_(handle), name_(std::move(path)), flags_(flags), last_mod_time_(last_mod_time) {
  LOG(ERROR) << "This function is not supported and will result in "
                "poor performance and/or crashes. Stop calling it.";
}

ZipAssetsProvider::ZipAssetsProvider(ZipArchiveHandle handle, PathOrDebugName&& path,
                                     ModDate last_mod_time, package_property_t flags)
    : zip_handle_(handle), name_(std::move(path)), flags_(flags), last_mod_time_(last_mod_time) {
}

std::unique_ptr<ZipAssetsProvider> ZipAssetsProvider::Create(std::string path,
                                                             package_property_t flags,
                                                             base::unique_fd fd) {
  const auto released_fd = fd.ok() ? fd.release() : -1;
  ZipArchiveHandle handle;
  if (int32_t result = released_fd < 0 ? OpenArchive(path.c_str(), &handle)
                                       : OpenArchiveFd(released_fd, path.c_str(), &handle)) {
    LOG(ERROR) << "Failed to open APK '" << path << "': " << ::ErrorCodeString(result);
    CloseArchive(handle);
    return {};
  }

  ModDate mod_date = kInvalidModDate;
  // Skip all up-to-date checks if the file won't ever change.
  if (isKnownWritablePath(path.c_str()) || !isReadonlyFilesystem(GetFileDescriptor(handle))) {
    if (mod_date = getFileModDate(GetFileDescriptor(handle)); mod_date == kInvalidModDate) {
      // Stat requires execute permissions on all directories path to the file. If the process does
      // not have execute permissions on this file, allow the zip to be opened but IsUpToDate() will
      // always have to return true.
      PLOG(WARNING) << "Failed to stat file '" << path << "'";
    }
  }

  return std::unique_ptr<ZipAssetsProvider>(
      new ZipAssetsProvider(handle, PathOrDebugName::Path(std::move(path)), mod_date, flags));
}

std::unique_ptr<ZipAssetsProvider> ZipAssetsProvider::Create(base::unique_fd fd,
                                                             std::string friendly_name,
                                                             package_property_t flags,
                                                             off64_t offset,
                                                             off64_t len) {
  ZipArchiveHandle handle;
  const int released_fd = fd.release();
  const int32_t result = (len == AssetsProvider::kUnknownLength)
      ? ::OpenArchiveFd(released_fd, friendly_name.c_str(), &handle)
      : ::OpenArchiveFdRange(released_fd, friendly_name.c_str(), &handle, len, offset);

  if (result != 0) {
    LOG(ERROR) << "Failed to open APK '" << friendly_name << "' through FD with offset " << offset
               << " and length " << len << ": " << ::ErrorCodeString(result);
    CloseArchive(handle);
    return {};
  }

  ModDate mod_date = kInvalidModDate;
  // Skip all up-to-date checks if the file won't ever change.
  if (!isReadonlyFilesystem(released_fd)) {
    if (mod_date = getFileModDate(released_fd); mod_date == kInvalidModDate) {
      // Stat requires execute permissions on all directories path to the file. If the process does
      // not have execute permissions on this file, allow the zip to be opened but IsUpToDate() will
      // always have to return true.
      LOG(WARNING) << "Failed to fstat file '" << friendly_name
                   << "': " << base::SystemErrorCodeToString(errno);
    }
  }

  return std::unique_ptr<ZipAssetsProvider>(new ZipAssetsProvider(
      handle, PathOrDebugName::DebugName(std::move(friendly_name)), mod_date, flags));
}

std::unique_ptr<Asset> ZipAssetsProvider::OpenInternal(const std::string& path,
                                                       Asset::AccessMode mode,
                                                       bool* file_exists) const {
    if (file_exists != nullptr) {
      *file_exists = false;
    }

    ZipEntry entry;
    if (FindEntry(zip_handle_.get(), path, &entry) != 0) {
      return {};
    }

    if (file_exists != nullptr) {
      *file_exists = true;
    }

    const int fd = GetFileDescriptor(zip_handle_.get());
    const off64_t fd_offset = GetFileDescriptorOffset(zip_handle_.get());
    const bool incremental_hardening = (flags_ & PROPERTY_DISABLE_INCREMENTAL_HARDENING) == 0U;
    incfs::IncFsFileMap asset_map;
    if (entry.method == kCompressDeflated) {
      if (!asset_map.Create(fd, entry.offset + fd_offset, entry.compressed_length,
                            name_.GetDebugName().c_str(), incremental_hardening)) {
        LOG(ERROR) << "Failed to mmap file '" << path << "' in APK '" << name_.GetDebugName()
                   << "'";
        return {};
      }

      std::unique_ptr<Asset> asset =
          Asset::createFromCompressedMap(std::move(asset_map), entry.uncompressed_length, mode);
      if (asset == nullptr) {
        LOG(ERROR) << "Failed to decompress '" << path << "' in APK '" << name_.GetDebugName()
                   << "'";
        return {};
      }
      return asset;
    }

    if (!asset_map.Create(fd, entry.offset + fd_offset, entry.uncompressed_length,
                          name_.GetDebugName().c_str(), incremental_hardening)) {
      LOG(ERROR) << "Failed to mmap file '" << path << "' in APK '" << name_.GetDebugName() << "'";
      return {};
    }

    base::unique_fd ufd;
    if (name_.GetPath() == nullptr) {
      // If the zip name does not represent a path, create a new `fd` for the new Asset to own in
      // order to create new file descriptors using Asset::openFileDescriptor. If the zip name is a
      // path, it will be used to create new file descriptors.
      ufd = base::unique_fd(dup(fd));
      if (!ufd.ok()) {
        LOG(ERROR) << "Unable to dup fd '" << path << "' in APK '" << name_.GetDebugName() << "'";
        return {};
      }
    }

    auto asset = Asset::createFromUncompressedMap(std::move(asset_map), mode, std::move(ufd));
    if (asset == nullptr) {
      LOG(ERROR) << "Failed to mmap file '" << path << "' in APK '" << name_.GetDebugName() << "'";
      return {};
    }
    return asset;
}

bool ZipAssetsProvider::ForEachFile(
    const std::string& root_path,
    base::function_ref<void(StringPiece, FileType)> f) const {
    std::string root_path_full = root_path;
    if (root_path_full.back() != '/') {
      root_path_full += '/';
    }

    void* cookie;
    if (StartIteration(zip_handle_.get(), &cookie, root_path_full, "") != 0) {
      return false;
    }

    std::string name;
    ::ZipEntry entry{};

    // We need to hold back directories because many paths will contain them and we want to only
    // surface one.
    std::set<std::string> dirs{};

    int32_t result;
    while ((result = Next(cookie, &entry, &name)) == 0) {
      StringPiece full_file_path(name);
      StringPiece leaf_file_path = full_file_path.substr(root_path_full.size());

      if (!leaf_file_path.empty()) {
        auto iter = std::find(leaf_file_path.begin(), leaf_file_path.end(), '/');
        if (iter != leaf_file_path.end()) {
          std::string dir(leaf_file_path.substr(0, std::distance(leaf_file_path.begin(), iter)));
          dirs.insert(std::move(dir));
        } else {
          f(leaf_file_path, kFileTypeRegular);
        }
      }
    }
    EndIteration(cookie);

    // Now present the unique directories.
    for (const std::string& dir : dirs) {
      f(dir, kFileTypeDirectory);
    }

    // -1 is end of iteration, anything else is an error.
    return result == -1;
}

std::optional<uint32_t> ZipAssetsProvider::GetCrc(std::string_view path) const {
  ::ZipEntry entry;
  if (FindEntry(zip_handle_.get(), path, &entry) != 0) {
    return {};
  }
  return entry.crc32;
}

std::optional<std::string_view> ZipAssetsProvider::GetPath() const {
  if (name_.GetPath() != nullptr) {
    return *name_.GetPath();
  }
  return {};
}

const std::string& ZipAssetsProvider::GetDebugName() const {
  return name_.GetDebugName();
}

UpToDate ZipAssetsProvider::IsUpToDate() const {
  if (last_mod_time_ == kInvalidModDate) {
    return UpToDate::Always;
  }
  return fromBool(last_mod_time_ == getFileModDate(GetFileDescriptor(zip_handle_.get())));
}

DirectoryAssetsProvider::DirectoryAssetsProvider(std::string&& path, ModDate last_mod_time)
    : dir_(std::move(path)), last_mod_time_(last_mod_time) {
}

std::unique_ptr<DirectoryAssetsProvider> DirectoryAssetsProvider::Create(std::string path) {
  struct stat sb;
  const int result = stat(path.c_str(), &sb);
  if (result == -1) {
    LOG(ERROR) << "Failed to find directory '" << path << "'.";
    return nullptr;
  }

  if (!S_ISDIR(sb.st_mode)) {
    LOG(ERROR) << "Path '" << path << "' is not a directory.";
    return nullptr;
  }

  if (path.back() != OS_PATH_SEPARATOR) {
    path += OS_PATH_SEPARATOR;
  }

  const bool isReadonly = isReadonlyFilesystem(path.c_str());
  return std::unique_ptr<DirectoryAssetsProvider>(
      new DirectoryAssetsProvider(std::move(path), isReadonly ? kInvalidModDate : getModDate(sb)));
}

std::unique_ptr<Asset> DirectoryAssetsProvider::OpenInternal(const std::string& path,
                                                             Asset::AccessMode /* mode */,
                                                             bool* file_exists) const {
  const std::string resolved_path = dir_ + path;
  if (file_exists != nullptr) {
    struct stat sb{};
    *file_exists = (stat(resolved_path.c_str(), &sb) != -1) && S_ISREG(sb.st_mode);
  }

  return CreateAssetFromFile(resolved_path);
}

bool DirectoryAssetsProvider::ForEachFile(
    const std::string& /* root_path */,
    base::function_ref<void(StringPiece, FileType)> /* f */) const {
  return true;
}

std::optional<std::string_view> DirectoryAssetsProvider::GetPath() const {
  return dir_;
}

const std::string& DirectoryAssetsProvider::GetDebugName() const {
  return dir_;
}

UpToDate DirectoryAssetsProvider::IsUpToDate() const {
  if (last_mod_time_ == kInvalidModDate) {
    return UpToDate::Always;
  }
  return fromBool(last_mod_time_ == getFileModDate(dir_.c_str()));
}

MultiAssetsProvider::MultiAssetsProvider(std::unique_ptr<AssetsProvider>&& primary,
                                         std::unique_ptr<AssetsProvider>&& secondary)
    : primary_(std::move(primary)), secondary_(std::move(secondary)) {
  debug_name_ = primary_->GetDebugName() + " and " + secondary_->GetDebugName();
  path_ = (primary_->GetDebugName() != kEmptyDebugString) ? primary_->GetPath()
                                                          : secondary_->GetPath();
}

std::unique_ptr<AssetsProvider> MultiAssetsProvider::Create(
    std::unique_ptr<AssetsProvider>&& primary, std::unique_ptr<AssetsProvider>&& secondary) {
  if (primary == nullptr || secondary == nullptr) {
    return nullptr;
  }
  return std::unique_ptr<MultiAssetsProvider>(new MultiAssetsProvider(std::move(primary),
                                                                      std::move(secondary)));
}

std::unique_ptr<Asset> MultiAssetsProvider::OpenInternal(const std::string& path,
                                                         Asset::AccessMode mode,
                                                         bool* file_exists) const {
  auto asset = primary_->Open(path, mode, file_exists);
  return (asset) ? std::move(asset) : secondary_->Open(path, mode, file_exists);
}

bool MultiAssetsProvider::ForEachFile(
    const std::string& root_path,
    base::function_ref<void(StringPiece, FileType)> f) const {
  return primary_->ForEachFile(root_path, f) && secondary_->ForEachFile(root_path, f);
}

std::optional<std::string_view> MultiAssetsProvider::GetPath() const {
  return path_;
}

const std::string& MultiAssetsProvider::GetDebugName() const {
  return debug_name_;
}

UpToDate MultiAssetsProvider::IsUpToDate() const {
  return combine(primary_->IsUpToDate(), [this] { return secondary_->IsUpToDate(); });
}

EmptyAssetsProvider::EmptyAssetsProvider(std::optional<std::string>&& path) :
    path_(std::move(path)) {}

std::unique_ptr<AssetsProvider> EmptyAssetsProvider::Create() {
  return std::unique_ptr<EmptyAssetsProvider>(new EmptyAssetsProvider({}));
}

std::unique_ptr<AssetsProvider> EmptyAssetsProvider::Create(std::string path) {
  return std::unique_ptr<EmptyAssetsProvider>(new EmptyAssetsProvider(std::move(path)));
}

std::unique_ptr<Asset> EmptyAssetsProvider::OpenInternal(const std::string& /* path */,
                                                         Asset::AccessMode /* mode */,
                                                         bool* file_exists) const {
  if (file_exists) {
    *file_exists = false;
  }
  return nullptr;
}

bool EmptyAssetsProvider::ForEachFile(
    const std::string& /* root_path */,
    base::function_ref<void(StringPiece, FileType)> /* f */) const {
  return true;
}

std::optional<std::string_view> EmptyAssetsProvider::GetPath() const {
  if (path_.has_value()) {
    return *path_;
  }
  return {};
}

const std::string& EmptyAssetsProvider::GetDebugName() const {
  if (path_.has_value()) {
    return *path_;
  }
  constexpr static std::string kEmpty{kEmptyDebugString};
  return kEmpty;
}

UpToDate EmptyAssetsProvider::IsUpToDate() const {
  return UpToDate::Always;
}

}  // namespace android
