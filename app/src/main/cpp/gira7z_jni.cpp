#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#include "Common/MyWindows.h"
#include "Common/MyInitGuid.h"
#include "Common/MyCom.h"
#include "Common/MyString.h"
#include "Common/UTFConvert.h"
#include "Windows/PropVariant.h"
#include "Windows/PropVariantConv.h"
#include "7zip/Archive/IArchive.h"
#include "7zip/Archive/7z/7zHandler.h"
#include "7zip/IPassword.h"
#include "7zip/IStream.h"
#include "../C/7zCrc.h"

using NWindows::NCOM::CPropVariant;

namespace {

static std::once_flag g_crc_once;
static void ensureCrcInit() {
    std::call_once(g_crc_once, []() {
        CrcGenerateTable();
    });
}

static uint64_t nowMs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000ULL + (uint64_t)(ts.tv_nsec / 1000000ULL);
}

static UString jstringToUString(JNIEnv *env, jstring jstr) {
    UString out;
    if (!jstr) return out;
    jsize len = env->GetStringLength(jstr);
    if (len <= 0) return out;
    const jchar *chars = env->GetStringChars(jstr, nullptr);
    if (!chars) return out;
    for (jsize i = 0; i < len; ++i) {
        uint32_t c = chars[i];
        if (c >= 0xD800 && c <= 0xDBFF && i + 1 < len) {
            uint32_t c2 = chars[i + 1];
            if (c2 >= 0xDC00 && c2 <= 0xDFFF) {
                uint32_t cp = 0x10000 + (((c - 0xD800) << 10) | (c2 - 0xDC00));
                out += (wchar_t)cp;
                ++i;
                continue;
            }
        }
        out += (wchar_t)c;
    }
    env->ReleaseStringChars(jstr, chars);
    return out;
}

static std::string ustringToUtf8(const UString &u) {
    AString a;
    ConvertUnicodeToUTF8(u, a);
    return std::string(a.Ptr(), a.Len());
}

static bool sanitizeRelPath(const std::string &raw, std::string &cleanOut) {
    if (raw.empty()) return false;
    std::string norm = raw;
    for (char &ch : norm) {
        if (ch == '\\') ch = '/';
    }
    if (norm[0] == '/') return false;
    std::vector<std::string> parts;
    size_t i = 0;
    while (i < norm.size()) {
        size_t j = norm.find('/', i);
        if (j == std::string::npos) j = norm.size();
        std::string seg = norm.substr(i, j - i);
        if (seg == "..") return false;
        if (!seg.empty() && seg != ".") {
            parts.push_back(seg);
        }
        i = j + 1;
    }
    if (parts.empty()) return false;
    cleanOut.clear();
    for (size_t k = 0; k < parts.size(); ++k) {
        if (k > 0) cleanOut.push_back('/');
        cleanOut += parts[k];
    }
    return true;
}

static bool mkdirP(const std::string &dir) {
    if (dir.empty()) return false;
    struct stat st;
    if (stat(dir.c_str(), &st) == 0 && S_ISDIR(st.st_mode)) return true;
    std::string cur;
    cur.reserve(dir.size());
    size_t i = 0;
    if (dir[0] == '/') {
        cur.push_back('/');
        i = 1;
    }
    while (i < dir.size()) {
        size_t j = dir.find('/', i);
        if (j == std::string::npos) j = dir.size();
        std::string seg = dir.substr(i, j - i);
        if (!seg.empty()) {
            if (cur.size() > 1 || (cur.size() == 1 && cur[0] != '/')) {
                cur.push_back('/');
            }
            cur += seg;
            if (mkdir(cur.c_str(), 0755) != 0 && errno != EEXIST) {
                return false;
            }
        }
        i = j + 1;
    }
    return true;
}

static void throwRuntime(JNIEnv *env, const std::string &msg) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    if (cls) {
        env->ThrowNew(cls, msg.c_str());
    }
}

// Multi-volume buffered IInStream over POSIX pread() (supports single .7z and split .7z.001/.002/...)
class CMultiPartInStream final :
    public IInStream,
    public IStreamGetSize,
    public CMyUnknownImp
{
    Z7_COM_UNKNOWN_IMP_3(
        IInStream,
        ISequentialInStream,
        IStreamGetSize)

public:
    struct Part {
        int fd;
        uint64_t start;
        uint64_t size;
    };

    std::vector<Part> _parts;
    uint64_t _totalSize = 0;
    uint64_t _pos = 0;

    static constexpr size_t kBufSize = 4 * 1024 * 1024;
    std::vector<uint8_t> _buf;
    uint64_t _bufStart = 0;
    size_t _bufLen = 0;
    bool _bufValid = false;
    std::mutex _mtx;

    CMultiPartInStream() : _buf(kBufSize) {}

    ~CMultiPartInStream() {
        for (const auto &p : _parts) {
            if (p.fd >= 0) close(p.fd);
        }
        _parts.clear();
    }

    bool Open(const std::vector<std::string> &paths, std::string &errOut) {
        _totalSize = 0;
        for (const auto &path : paths) {
            int fd = open(path.c_str(), O_RDONLY);
            if (fd < 0) {
                errOut = "Cannot open archive part '" + path + "': " + strerror(errno);
                return false;
            }
            struct stat st;
            if (fstat(fd, &st) != 0) {
                close(fd);
                errOut = "Cannot stat archive part '" + path + "': " + strerror(errno);
                return false;
            }
            uint64_t sz = (uint64_t)st.st_size;
            _parts.push_back(Part{fd, _totalSize, sz});
            _totalSize += sz;
        }
        return !_parts.empty();
    }

    HRESULT ReadDirect(uint64_t offset, void *dst, size_t len, size_t &readOut) {
        readOut = 0;
        if (offset >= _totalSize || len == 0) return S_OK;
        uint8_t *out = static_cast<uint8_t *>(dst);
        size_t want = (size_t)std::min<uint64_t>(len, _totalSize - offset);
        // Binary search starting part
        size_t idx = 0;
        size_t lo = 0, hi = _parts.size();
        while (lo + 1 < hi) {
            size_t mid = lo + (hi - lo) / 2;
            if (_parts[mid].start <= offset) lo = mid;
            else hi = mid;
        }
        idx = lo;
        while (readOut < want && idx < _parts.size()) {
            const Part &p = _parts[idx];
            if (offset < p.start + p.size) {
                uint64_t localOff = offset - p.start;
                size_t chunk = (size_t)std::min<uint64_t>(want - readOut, p.size - localOff);
                while (chunk > 0) {
                    ssize_t n = pread(p.fd, out + readOut, chunk, (off_t)localOff);
                    if (n < 0) {
                        if (errno == EINTR) continue;
                        return HRESULT_FROM_WIN32(errno);
                    }
                    if (n == 0) break;
                    readOut += (size_t)n;
                    offset += (uint64_t)n;
                    localOff += (uint64_t)n;
                    chunk -= (size_t)n;
                }
            }
            ++idx;
        }
        return S_OK;
    }

    Z7_COM7F_IMF(Read(void *data, UInt32 size, UInt32 *processedSize)) {
        std::lock_guard<std::mutex> lock(_mtx);
        if (processedSize) *processedSize = 0;
        if (size == 0 || _pos >= _totalSize) return S_OK;

        if (_bufValid && _pos >= _bufStart && _pos < _bufStart + _bufLen) {
            size_t off = (size_t)(_pos - _bufStart);
            size_t avail = std::min<size_t>(_bufLen - off, size);
            memcpy(data, _buf.data() + off, avail);
            _pos += avail;
            if (processedSize) *processedSize = (UInt32)avail;
            return S_OK;
        }

        if (size >= kBufSize / 2) {
            size_t got = 0;
            HRESULT hr = ReadDirect(_pos, data, size, got);
            if (FAILED(hr)) return hr;
            _pos += got;
            if (processedSize) *processedSize = (UInt32)got;
            return S_OK;
        }

        size_t filled = 0;
        HRESULT hr = ReadDirect(_pos, _buf.data(), kBufSize, filled);
        if (FAILED(hr)) return hr;
        if (filled == 0) return S_OK;
        _bufStart = _pos;
        _bufLen = filled;
        _bufValid = true;
        size_t avail = std::min<size_t>(_bufLen, size);
        memcpy(data, _buf.data(), avail);
        _pos += avail;
        if (processedSize) *processedSize = (UInt32)avail;
        return S_OK;
    }

    Z7_COM7F_IMF(Seek(Int64 offset, UInt32 seekOrigin, UInt64 *newPosition)) {
        std::lock_guard<std::mutex> lock(_mtx);
        int64_t target = 0;
        switch (seekOrigin) {
            case STREAM_SEEK_SET: target = offset; break;
            case STREAM_SEEK_CUR: target = (int64_t)_pos + offset; break;
            case STREAM_SEEK_END: target = (int64_t)_totalSize + offset; break;
            default: return STG_E_INVALIDFUNCTION;
        }
        if (target < 0) return HRESULT_FROM_WIN32(EINVAL);
        _pos = (uint64_t)target;
        if (newPosition) *newPosition = _pos;
        return S_OK;
    }

    Z7_COM7F_IMF(GetSize(UInt64 *size)) {
        if (size) *size = _totalSize;
        return S_OK;
    }
};

class CBufferedFdOutStream final :
    public ISequentialOutStream,
    public CMyUnknownImp
{
    Z7_COM_UNKNOWN_IMP_1(ISequentialOutStream)

public:
    int _fd = -1;
    std::vector<uint8_t> _buf;
    size_t _bufUsed = 0;
    std::atomic<uint64_t> *_writtenCounter = nullptr;
    int _lastErrno = 0;

    static constexpr size_t kBufCap = 512 * 1024;

    CBufferedFdOutStream(int fd, std::atomic<uint64_t> *writtenCounter)
        : _fd(fd), _buf(kBufCap), _writtenCounter(writtenCounter) {}

    ~CBufferedFdOutStream() {
        FlushAndClose();
    }

    bool FlushBuffer() {
        if (_fd < 0 || _bufUsed == 0) return true;
        size_t sent = 0;
        while (sent < _bufUsed) {
            ssize_t n = write(_fd, _buf.data() + sent, _bufUsed - sent);
            if (n < 0) {
                if (errno == EINTR) continue;
                _lastErrno = errno;
                return false;
            }
            sent += (size_t)n;
        }
        _bufUsed = 0;
        return true;
    }

    HRESULT FlushAndClose() {
        bool ok = FlushBuffer();
        if (_fd >= 0) {
            if (close(_fd) != 0 && ok) {
                _lastErrno = errno;
                ok = false;
            }
            _fd = -1;
        }
        return ok ? S_OK : HRESULT_FROM_WIN32(_lastErrno ? _lastErrno : EIO);
    }

    Z7_COM7F_IMF(Write(const void *data, UInt32 size, UInt32 *processedSize)) {
        if (processedSize) *processedSize = 0;
        if (size == 0) return S_OK;
        const uint8_t *p = static_cast<const uint8_t *>(data);
        size_t rem = size;
        while (rem > 0) {
            size_t space = kBufCap - _bufUsed;
            if (rem >= kBufCap && _bufUsed == 0) {
                ssize_t n = write(_fd, p, rem);
                if (n < 0) {
                    if (errno == EINTR) continue;
                    _lastErrno = errno;
                    return HRESULT_FROM_WIN32(_lastErrno);
                }
                p += n;
                rem -= (size_t)n;
                if (_writtenCounter) *_writtenCounter += (uint64_t)n;
            } else {
                size_t take = std::min(space, rem);
                memcpy(_buf.data() + _bufUsed, p, take);
                _bufUsed += take;
                p += take;
                rem -= take;
                if (_writtenCounter) *_writtenCounter += (uint64_t)take;
                if (_bufUsed == kBufCap) {
                    if (!FlushBuffer()) return HRESULT_FROM_WIN32(_lastErrno ? _lastErrno : EIO);
                }
            }
        }
        if (processedSize) *processedSize = size;
        return S_OK;
    }
};

class COpenCallback final :
    public IArchiveOpenCallback,
    public ICryptoGetTextPassword,
    public CMyUnknownImp
{
    Z7_COM_UNKNOWN_IMP_2(IArchiveOpenCallback, ICryptoGetTextPassword)
public:
    bool PasswordDefined = false;
    UString Password;

    Z7_COM7F_IMF(SetTotal(const UInt64 *, const UInt64 *)) { return S_OK; }
    Z7_COM7F_IMF(SetCompleted(const UInt64 *, const UInt64 *)) { return S_OK; }
    Z7_COM7F_IMF(CryptoGetTextPassword(BSTR *password)) {
        if (!PasswordDefined) return E_ABORT;
        return StringToBstr(Password, password);
    }
};

struct ProgressBridge {
    JavaVM *jvm = nullptr;
    jobject cbGlobal = nullptr;
    jmethodID mid = nullptr;
    uint64_t totalBytes = 0;
    int lastPct = -1;
    uint64_t lastNotifyMs = 0;
    std::mutex mtx;

    void report(uint64_t written, const std::string &entryName, bool force) {
        if (!jvm || !cbGlobal || !mid || totalBytes == 0) return;
        {
            std::lock_guard<std::mutex> lock(mtx);
            int pct = (int)std::min<uint64_t>(100, (written * 100ULL) / totalBytes);
            uint64_t t = nowMs();
            if (!force && pct == lastPct && (t - lastNotifyMs) < 250ULL) return;
            lastPct = pct;
            lastNotifyMs = t;
        }
        JNIEnv *env = nullptr;
        if (jvm->GetEnv((void **)&env, JNI_VERSION_1_6) == JNI_EDETACHED) {
#ifdef __ANDROID__
            if (jvm->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK) {
#else
            if (jvm->AttachCurrentThreadAsDaemon((void **)&env, nullptr) != JNI_OK) {
#endif
                return;
            }
        }
        if (!env) return;
        jstring jname = env->NewStringUTF(entryName.c_str());
        env->CallVoidMethod(cbGlobal, mid, (jlong)written, (jlong)totalBytes, jname);
        if (jname) env->DeleteLocalRef(jname);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    }
};

class CExtractCallback final :
    public IArchiveExtractCallback,
    public ICryptoGetTextPassword,
    public CMyUnknownImp
{
    Z7_COM_UNKNOWN_IMP_2(IArchiveExtractCallback, ICryptoGetTextPassword)

public:
    CMyComPtr<IInArchive> _archive;
    std::string _destDir;
    bool _resume = false;
    bool _passwordDefined = false;
    UString _password;
    ProgressBridge *_progress = nullptr;

    std::atomic<uint64_t> _writtenBytes{0};
    uint64_t _skippedEntries = 0;
    uint64_t _skippedBytes = 0;
    uint64_t _numErrors = 0;
    std::string _firstError;
    std::string _currentEntry;

    CBufferedFdOutStream *_outSpec = nullptr;
    CMyComPtr<ISequentialOutStream> _outRef;

    Z7_COM7F_IMF(SetTotal(UInt64 total)) {
        if (_progress && _progress->totalBytes == 0 && total > 0) {
            _progress->totalBytes = total;
        }
        return S_OK;
    }

    Z7_COM7F_IMF(SetCompleted(const UInt64 *completeValue)) {
        if (_progress) {
            uint64_t cur = _writtenBytes;
            if (cur == 0 && completeValue) cur = *completeValue;
            _progress->report(cur, _currentEntry, false);
        }
        return S_OK;
    }

    Z7_COM7F_IMF(GetStream(UInt32 index, ISequentialOutStream **outStream, Int32 askExtractMode)) {
        *outStream = nullptr;
        _outRef.Release();
        _outSpec = nullptr;

        CPropVariant propPath;
        RINOK(_archive->GetProperty(index, kpidPath, &propPath))
        UString rawU;
        if (propPath.vt == VT_BSTR && propPath.bstrVal) {
            rawU = propPath.bstrVal;
        }
        std::string rawUtf8 = ustringToUtf8(rawU);
        std::string relPath;
        if (!sanitizeRelPath(rawUtf8, relPath)) {
            if (rawUtf8.empty()) {
                relPath = "content";
            } else {
                _numErrors++;
                if (_firstError.empty()) _firstError = "Invalid archive path: " + rawUtf8;
                return E_ABORT;
            }
        }
        _currentEntry = relPath;

        if (askExtractMode != NArchive::NExtract::NAskMode::kExtract) {
            return S_OK;
        }

        bool isDir = false;
        {
            CPropVariant propDir;
            RINOK(_archive->GetProperty(index, kpidIsDir, &propDir))
            if (propDir.vt == VT_BOOL) isDir = VARIANT_BOOLToBool(propDir.boolVal);
        }

        bool sizeKnown = false;
        UInt64 entrySize = 0;
        {
            CPropVariant propSize;
            RINOK(_archive->GetProperty(index, kpidSize, &propSize))
            sizeKnown = ConvertPropVariantToUInt64(propSize, entrySize);
        }

        std::string fullPath = _destDir + "/" + relPath;
        if (isDir) {
            if (!mkdirP(fullPath)) {
                _numErrors++;
                if (_firstError.empty()) _firstError = "mkdir failed: " + fullPath + " (" + strerror(errno) + ")";
                return HRESULT_FROM_WIN32(errno);
            }
            return S_OK;
        }

        if (_resume && sizeKnown) {
            struct stat st;
            if (stat(fullPath.c_str(), &st) == 0 && S_ISREG(st.st_mode) && (uint64_t)st.st_size == entrySize) {
                _skippedEntries++;
                _skippedBytes += entrySize;
                _writtenBytes += entrySize;
                if (_progress) _progress->report(_writtenBytes, relPath, false);
                return S_OK; // *outStream == nullptr => 7z skips this file
            }
        }

        size_t slash = fullPath.rfind('/');
        if (slash != std::string::npos) {
            std::string parent = fullPath.substr(0, slash);
            if (!mkdirP(parent)) {
                _numErrors++;
                if (_firstError.empty()) _firstError = "mkdir parent failed: " + parent + " (" + strerror(errno) + ")";
                return HRESULT_FROM_WIN32(errno);
            }
        }

        unlink(fullPath.c_str());
        int fd = open(fullPath.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd < 0) {
            _numErrors++;
            if (_firstError.empty()) _firstError = "open output failed: " + fullPath + " (" + strerror(errno) + ")";
            return HRESULT_FROM_WIN32(errno);
        }

        _outSpec = new CBufferedFdOutStream(fd, &_writtenBytes);
        _outRef = _outSpec;
        *outStream = _outRef;
        (*outStream)->AddRef();
        return S_OK;
    }

    Z7_COM7F_IMF(PrepareOperation(Int32)) { return S_OK; }

    Z7_COM7F_IMF(SetOperationResult(Int32 opResult)) {
        if (_outSpec) {
            HRESULT flushHr = _outSpec->FlushAndClose();
            if (FAILED(flushHr)) {
                _numErrors++;
                if (_firstError.empty()) {
                    _firstError = "Write/close error on '" + _currentEntry + "': " + strerror(_outSpec->_lastErrno ? _outSpec->_lastErrno : EIO);
                }
            }
            _outSpec = nullptr;
        }
        _outRef.Release();

        if (opResult != NArchive::NExtract::NOperationResult::kOK) {
            _numErrors++;
            if (_firstError.empty()) {
                const char *msg = "Extraction error";
                switch (opResult) {
                    case NArchive::NExtract::NOperationResult::kUnsupportedMethod: msg = "Unsupported compression method"; break;
                    case NArchive::NExtract::NOperationResult::kCRCError: msg = "CRC failed (wrong password or corrupt data)"; break;
                    case NArchive::NExtract::NOperationResult::kDataError: msg = "Data error (wrong password or corrupt archive)"; break;
                    case NArchive::NExtract::NOperationResult::kUnavailable: msg = "Unavailable data"; break;
                    case NArchive::NExtract::NOperationResult::kUnexpectedEnd: msg = "Unexpected end of archive"; break;
                    case NArchive::NExtract::NOperationResult::kDataAfterEnd: msg = "Data after end of payload"; break;
                    case NArchive::NExtract::NOperationResult::kIsNotArc: msg = "Not a valid 7z archive"; break;
                    case NArchive::NExtract::NOperationResult::kHeadersError: msg = "Headers error"; break;
                    case NArchive::NExtract::NOperationResult::kWrongPassword: msg = "Wrong password"; break;
                }
                _firstError = std::string(msg) + " (" + _currentEntry + ")";
            }
            return E_ABORT;
        }
        return S_OK;
    }

    Z7_COM7F_IMF(CryptoGetTextPassword(BSTR *password)) {
        if (!_passwordDefined) return E_ABORT;
        return StringToBstr(_password, password);
    }
};

} // namespace

extern "C" bool Gira7z_ExtractFiles(
    const std::vector<std::string> &partPaths,
    const std::string &destDir,
    const UString &password,
    bool passwordDefined,
    bool resume,
    uint32_t numThreads,
    ProgressBridge *progress,
    uint64_t &writtenOut,
    uint64_t &skippedEntriesOut,
    uint64_t &skippedBytesOut,
    std::string &errorOut)
{
    ensureCrcInit();
    if (partPaths.empty()) {
        errorOut = "No archive parts specified";
        return false;
    }
    if (!mkdirP(destDir)) {
        errorOut = "Cannot create destination dir: " + destDir;
        return false;
    }

    CMultiPartInStream *inStreamSpec = new CMultiPartInStream();
    CMyComPtr<IInStream> inStream(inStreamSpec);
    if (!inStreamSpec->Open(partPaths, errorOut)) {
        return false;
    }

    NArchive::N7z::CHandler *handlerSpec = new NArchive::N7z::CHandler();
    CMyComPtr<IInArchive> archive(handlerSpec);

    // Enable multi-threaded LZMA2/filter mixer
    {
        CMyComPtr<ISetProperties> setProps;
        archive->QueryInterface(IID_ISetProperties, (void **)&setProps);
        if (setProps) {
            const wchar_t *names[2] = { L"mt", L"mtf" };
            CPropVariant vals[2];
            vals[0] = (UInt32)numThreads;
            vals[1] = true;
            setProps->SetProperties(names, vals, 2);
        }
    }

    COpenCallback *openCbSpec = new COpenCallback();
    CMyComPtr<IArchiveOpenCallback> openCb(openCbSpec);
    openCbSpec->PasswordDefined = passwordDefined;
    openCbSpec->Password = password;

    const UInt64 scanLimit = (1 << 23);
    HRESULT hr = archive->Open(inStream, &scanLimit, openCb);
    if (hr != S_OK) {
        errorOut = "Cannot open 7z archive (HRESULT 0x" + std::to_string((uint32_t)hr) + ", wrong password or corrupt headers)";
        return false;
    }

    UInt32 numItems = 0;
    archive->GetNumberOfItems(&numItems);
    uint64_t totalSize = 0;
    for (UInt32 i = 0; i < numItems; ++i) {
        CPropVariant propDir, propSize;
        bool isDir = false;
        if (archive->GetProperty(i, kpidIsDir, &propDir) == S_OK && propDir.vt == VT_BOOL) {
            isDir = VARIANT_BOOLToBool(propDir.boolVal);
        }
        if (!isDir && archive->GetProperty(i, kpidSize, &propSize) == S_OK) {
            UInt64 sz = 0;
            if (ConvertPropVariantToUInt64(propSize, sz)) {
                totalSize += sz;
            }
        }
    }
    if (progress) progress->totalBytes = totalSize;

    CExtractCallback *extCbSpec = new CExtractCallback();
    CMyComPtr<IArchiveExtractCallback> extCb(extCbSpec);
    extCbSpec->_archive = archive;
    extCbSpec->_destDir = destDir;
    extCbSpec->_resume = resume;
    extCbSpec->_passwordDefined = passwordDefined;
    extCbSpec->_password = password;
    extCbSpec->_progress = progress;

    hr = archive->Extract(nullptr, (UInt32)(Int32)-1, false, extCb);
    if (progress) progress->report(extCbSpec->_writtenBytes, "", true);

    writtenOut = extCbSpec->_writtenBytes;
    skippedEntriesOut = extCbSpec->_skippedEntries;
    skippedBytesOut = extCbSpec->_skippedBytes;

    if (hr != S_OK || extCbSpec->_numErrors > 0) {
        if (!extCbSpec->_firstError.empty()) {
            errorOut = extCbSpec->_firstError;
        } else {
            errorOut = "7z extraction failed (HRESULT 0x" + std::to_string((uint32_t)hr) + ")";
        }
        archive->Close();
        return false;
    }
    archive->Close();
    return true;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_es_girarkas_giraquest_download_NativeSevenZip_nativeExtract7z(
    JNIEnv *env,
    jclass /* clazz */,
    jobjectArray jParts,
    jstring jDestDir,
    jstring jPassword,
    jboolean jResume,
    jint jThreads,
    jobject jCallback)
{
    if (!jParts || !jDestDir) {
        throwRuntime(env, "nativeExtract7z: null arguments");
        return nullptr;
    }

    jsize count = env->GetArrayLength(jParts);
    std::vector<std::string> parts;
    parts.reserve((size_t)count);
    for (jsize i = 0; i < count; ++i) {
        jstring js = (jstring)env->GetObjectArrayElement(jParts, i);
        if (!js) continue;
        const char *utf = env->GetStringUTFChars(js, nullptr);
        if (utf) {
            parts.emplace_back(utf);
            env->ReleaseStringUTFChars(js, utf);
        }
        env->DeleteLocalRef(js);
    }

    std::string destDir;
    {
        const char *utf = env->GetStringUTFChars(jDestDir, nullptr);
        if (utf) {
            destDir = utf;
            env->ReleaseStringUTFChars(jDestDir, utf);
        }
    }
    while (destDir.size() > 1 && destDir.back() == '/') destDir.pop_back();

    bool pwDef = false;
    UString pw;
    if (jPassword) {
        pw = jstringToUString(env, jPassword);
        pwDef = !pw.IsEmpty();
    }

    uint32_t threads = (jThreads > 0 && jThreads <= 16) ? (uint32_t)jThreads : 6;

    ProgressBridge bridge;
    if (jCallback) {
        env->GetJavaVM(&bridge.jvm);
        jclass cbCls = env->GetObjectClass(jCallback);
        if (cbCls) {
            bridge.mid = env->GetMethodID(cbCls, "onProgress", "(JJLjava/lang/String;)V");
            env->DeleteLocalRef(cbCls);
        }
        if (bridge.mid && bridge.jvm) {
            bridge.cbGlobal = env->NewGlobalRef(jCallback);
        }
    }

    uint64_t written = 0, skippedEntries = 0, skippedBytes = 0;
    std::string err;
    bool ok = Gira7z_ExtractFiles(
        parts, destDir, pw, pwDef, jResume == JNI_TRUE, threads,
        bridge.cbGlobal ? &bridge : nullptr,
        written, skippedEntries, skippedBytes, err);

    if (bridge.cbGlobal) {
        env->DeleteGlobalRef(bridge.cbGlobal);
        bridge.cbGlobal = nullptr;
    }

    if (!ok) {
        throwRuntime(env, err);
        return nullptr;
    }

    jlongArray res = env->NewLongArray(3);
    if (res) {
        jlong vals[3] = { (jlong)written, (jlong)skippedEntries, (jlong)skippedBytes };
        env->SetLongArrayRegion(res, 0, 3, vals);
    }
    return res;
}
