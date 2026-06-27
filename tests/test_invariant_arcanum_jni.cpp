#include <gtest/gtest.h>
#include <string>
#include <vector>
#include <fstream>
#include <filesystem>
#include <cstdlib>

extern "C" {
    void apply_keyfiles_to_password(const std::vector<std::string>& paths,
                                    uint8_t *pwd_buf, int *pwd_len);
}

class SecurityTest : public ::testing::TestWithParam<std::string> {
protected:
    void SetUp() override {
        test_dir = "/data/local/tmp/test_keyfiles/";
        std::filesystem::create_directories(test_dir);
    }
    
    void TearDown() override {
        std::filesystem::remove_all(test_dir);
    }
    
    std::string test_dir;
};

TEST_P(SecurityTest, KeyfilesMustNotLeavePlaintextCopiesInCache) {
    // Invariant: Keyfile processing must not create persistent plaintext copies
    std::string payload = GetParam();
    std::string test_file = test_dir + "test.key";
    
    // Create test keyfile with payload
    std::ofstream file(test_file);
    file << payload;
    file.close();
    
    // Call production function
    uint8_t pwd_buf[256];
    int pwd_len = 0;
    std::vector<std::string> paths = {test_file};
    apply_keyfiles_to_password(paths, pwd_buf, &pwd_len);
    
    // Check that no cache copies remain
    std::string cache_dir = "/data/data/com.example.app/cache/";
    bool cache_copy_exists = false;
    
    for (const auto& entry : std::filesystem::directory_iterator(cache_dir)) {
        if (entry.is_regular_file()) {
            std::ifstream cache_file(entry.path());
            std::string content((std::istreambuf_iterator<char>(cache_file)),
                                std::istreambuf_iterator<char>());
            if (content == payload) {
                cache_copy_exists = true;
                break;
            }
        }
    }
    
    ASSERT_FALSE(cache_copy_exists) << "Plaintext keyfile copy found in cache directory";
}

INSTANTIATE_TEST_SUITE_P(
    AdversarialInputs,
    SecurityTest,
    ::testing::Values(
        // Exact exploit: actual key material that should never be cached
        "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC7VJTUt9Us8cHj\n",
        // Boundary case: empty keyfile
        "",
        // Valid input: normal keyfile content
        "test_key_material_12345",
        // Attack payload: path traversal attempt
        "../../../data/system/secret.key",
        // Attack payload: symlink target (tested via special handling)
        "/dev/urandom"
    )
);