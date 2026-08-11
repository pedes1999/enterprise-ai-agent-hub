package com.enterprisehub.runtime.tools;

/**
 * Embeds a sandbox env-var reference into a git HTTPS URL as Basic-auth
 * userinfo -- e.g. https://x-access-token:$GIT_TOKEN@github.com/org/repo.git.
 *
 * Found via live testing (against a real private repo) to be the reliable
 * auth mechanism in this sandbox's git/shell combination. An inline
 * `-c credential.helper='!f() { echo password=$TOKEN; }; f'` was tried
 * first -- it looked correct (git does support this exact pattern in
 * general) but silently failed to deliver the password line through to
 * git in practice here: GitHub's response ("Invalid username or token.
 * Password authentication is not supported") is the exact message it
 * gives when a username is presented with an effectively empty password,
 * consistent with the helper's output not reaching git intact. Switching
 * to URL-embedded Basic auth (confirmed working via a live `git
 * ls-remote` test using the identical token) sidesteps that helper
 * machinery entirely -- git only needs to parse a URL, no secondary
 * process invocation involved.
 *
 * The token is never written to disk by design: this returns a shell
 * expression built from a DOUBLE-quoted literal segment we control
 * (allowing $TOKEN to expand) concatenated, with no space, against a
 * SINGLE-quoted segment holding the caller-supplied (untrusted) remainder
 * of the URL (fully neutralizing any shell metacharacters in it) -- two
 * adjacent quoted segments with no space between them form one shell
 * word. Callers must either use the result for a one-shot operation that
 * never persists it (e.g. `git push &lt;url&gt; ...`, which never touches
 * .git/config) or immediately overwrite any remote it was used to
 * configure back to a credential-free URL in the SAME command chain (see
 * GitCloneTool).
 */
final class AuthenticatedGitUrl {

    private AuthenticatedGitUrl() {
    }

    static String build(String httpsRepositoryUrl, String tokenEnvVar) {
        String withoutScheme = httpsRepositoryUrl.substring("https://".length());
        return "\"https://x-access-token:$" + tokenEnvVar + "@\"" + ShellQuoting.quote(withoutScheme);
    }
}
