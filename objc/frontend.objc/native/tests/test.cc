#include <fts.h>

#include <exception>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "asserts.h"
#include "Indexer.h"
#include "ObjCIndex.pb.h"

template<typename Callback> void recurseIntoFiles(const std::string& startDir, Callback callback) {
    char *paths[] = {const_cast<char *>(startDir.c_str()), 0};
    FTS *tree = fts_open(paths, FTS_NOCHDIR, 0);
    if (!tree) failWithMsg("%s folder not found", startDir.c_str());

    FTSENT *node;
    while ((node = fts_read(tree))) {
        if (node->fts_level > 0 && node->fts_name[0] == '.') {
            fts_set(tree, node, FTS_SKIP);
        } else if (node->fts_info & FTS_F) {
            callback(node->fts_path);
        }
    }

    fts_close(tree);
}

bool endsWith(const std::string& string, const std::string& ending) {
    return string.length() >= ending.length() && !string.compare(string.length() - ending.length(), ending.length(), ending);
}

enum TestResult {
    OK,
    FIRST_RUN,
    FAIL,
    EXCEPTION
};

void renderResult(const std::string& filename, const TestResult& result) {
    std::string s;
    switch (result) {
        case OK: s = "OK"; break;
        case FIRST_RUN: s = "FIRST_RUN"; break;
        case FAIL: s = "FAIL"; break;
        case EXCEPTION: s = "EXCEPTION"; break;
        default: s = "UNKNOWN"; break;
    }
    printf("  %s  %s\n", s.c_str(), filename.c_str());
}

extern std::string *doIndex(const std::vector<std::string>& headers);

void doTest(const std::string& filename) {
    if (!endsWith(filename, ".h")) return;
    auto expectedFile = filename.substr(0, filename.length() - 1) + "out";

    std::vector<std::string> headers(1, filename);

    try {
        TranslationUnit tu;
        std::string *message = doIndex(headers);
        tu.ParseFromString(*message);
        auto actual = tu.DebugString();
        delete message;

        std::ifstream expectedStream(expectedFile.c_str());
        if (!expectedStream) {
            std::ofstream out(expectedFile.c_str());
            out << actual;
            renderResult(filename, FIRST_RUN);
            return;
        }
        std::stringstream expectedBuffer;
        expectedBuffer << expectedStream.rdbuf();
        std::string expected = expectedBuffer.str();

        if (expected != actual) {
            auto actualFile = expectedFile + ".actual";
            std::ofstream out(actualFile.c_str());
            out << actual;
            renderResult(filename, FAIL);
            return;
        }
    } catch (std::exception& e) {
        renderResult(filename, EXCEPTION);
        return;
    }

    renderResult(filename, OK);
}

int main(int argc, char *argv[]) {
    if (argc > 1) {
        for (int i = 1; i < argc; i++) {
            doTest(argv[i]);
        }
    } else {
        recurseIntoFiles("testData", doTest);
    }

    return 0;
}
