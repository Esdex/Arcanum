#include <check.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/wait.h>

// Simulate the vulnerable execution pattern
static int execute_shell_command(const char *command) {
    pid_t pid = fork();
    if (pid == 0) {
        // Child process
        execl("/bin/sh", "sh", "-c", command, NULL);
        _exit(127); // exec failed
    } else if (pid > 0) {
        // Parent process
        int status;
        waitpid(pid, &status, 0);
        return WEXITSTATUS(status);
    }
    return -1; // fork failed
}

START_TEST(test_shell_command_injection)
{
    // Invariant: Shell commands never include unsanitized user input
    const char *payloads[] = {
        "id",                    // Valid input (baseline)
        "id; echo injected",     // Command separator
        "id && echo injected",   // Logical operator
        "id$(echo injected)",    // Command substitution
        "id`echo injected`"      // Backtick substitution
    };
    int num_payloads = sizeof(payloads) / sizeof(payloads[0]);

    for (int i = 0; i < num_payloads; i++) {
        // Execute command and capture output
        int pipefd[2];
        pipe(pipefd);
        
        pid_t pid = fork();
        if (pid == 0) {
            close(pipefd[0]);
            dup2(pipefd[1], STDOUT_FILENO);
            close(pipefd[1]);
            
            // Simulate the vulnerable exec pattern
            char command[256];
            snprintf(command, sizeof(command), "su -c '%s'", payloads[i]);
            execl("/bin/sh", "sh", "-c", command, NULL);
            _exit(127);
        } else if (pid > 0) {
            close(pipefd[1]);
            
            char output[1024] = {0};
            read(pipefd[0], output, sizeof(output) - 1);
            close(pipefd[0]);
            
            int status;
            waitpid(pid, &status, 0);
            
            // Check if output contains "injected" - indicating command injection
            ck_assert_msg(strstr(output, "injected") == NULL, 
                         "Command injection detected in payload: %s", payloads[i]);
        }
    }
}
END_TEST

Suite *security_suite(void)
{
    Suite *s;
    TCase *tc_core;

    s = suite_create("Security");
    tc_core = tcase_create("Core");

    tcase_add_test(tc_core, test_shell_command_injection);
    suite_add_tcase(s, tc_core);

    return s;
}

int main(void)
{
    int number_failed;
    Suite *s;
    SRunner *sr;

    s = security_suite();
    sr = srunner_create(s);

    srunner_run_all(sr, CK_NORMAL);
    number_failed = srunner_ntests_failed(sr);
    srunner_free(sr);

    return (number_failed == 0) ? EXIT_SUCCESS : EXIT_FAILURE;
}