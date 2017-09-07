/*
 * Copyright (C) 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.git;

import static com.google.common.truth.Truth.assertThat;
import static com.google.copybara.testing.git.GitTestUtil.getGitEnv;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import com.google.api.client.http.HttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.copybara.CannotResolveRevisionException;
import com.google.copybara.Change;
import com.google.copybara.Origin.Reader;
import com.google.copybara.RepoException;
import com.google.copybara.ValidationException;
import com.google.copybara.authoring.Author;
import com.google.copybara.authoring.Authoring;
import com.google.copybara.authoring.Authoring.AuthoringMappingMode;
import com.google.copybara.testing.FileSubjects;
import com.google.copybara.testing.OptionsBuilder;
import com.google.copybara.testing.OptionsBuilder.GithubMockHttpTransport;
import com.google.copybara.testing.SkylarkTestExecutor;
import com.google.copybara.testing.git.GitTestUtil.TestGitOptions;
import com.google.copybara.util.Glob;
import com.google.copybara.util.console.testing.TestingConsole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Map.Entry;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GithubPrOriginTest {

  private Path repoGitDir;
  private OptionsBuilder options;
  private TestingConsole console;
  private SkylarkTestExecutor skylark;

  private final Authoring authoring = new Authoring(new Author("foo", "default@example.com"),
      AuthoringMappingMode.PASS_THRU, ImmutableSet.of());


  @Rule
  public final ExpectedException thrown = ExpectedException.none();
  private Path workdir;
  private Path localHub;
  private GithubMockHttpTransport githubMockHttpTransport;

  @Before
  public void setup() throws Exception {
    repoGitDir = Files.createTempDirectory("GithubPrDestinationTest-repoGitDir");
    workdir = Files.createTempDirectory("workdir");
    localHub = Files.createTempDirectory("localHub");

    git("init", "--bare", repoGitDir.toString());
    console = new TestingConsole();
    options = new OptionsBuilder()
        .setConsole(console)
        .setOutputRootToTmpDir();
    options.git = new TestGitOptions(localHub, () -> this.options.general);

    options.github = new GithubOptions(() -> options.general, options.git) {
      @Override
      protected HttpTransport getHttpTransport() {
        return githubMockHttpTransport;
      }
    };
    Path credentialsFile = Files.createTempFile("credentials", "test");
    Files.write(credentialsFile, "https://user:SECRET@github.com".getBytes(UTF_8));
    options.git.credentialHelperStorePath = credentialsFile.toString();

    skylark = new SkylarkTestExecutor(options, GitModule.class);
  }

  private GitRepository localHubRepo(String name) throws RepoException {
    GitRepository repo = GitRepository.newBareRepo(localHub.resolve("github.com/" + name),
        getGitEnv(),
        options.general.isVerbose());
    repo.init();
    return repo;
  }

  private String git(String... argv) throws RepoException {
    return repo()
        .git(repoGitDir, argv)
        .getStdout();
  }

  private GitRepository repo() {
    return repoForPath(repoGitDir);
  }

  private GitRepository repoForPath(Path path) {
    return GitRepository.newBareRepo(path, getGitEnv(),  /*verbose=*/true);
  }

  @Test
  public void testGitResolvePullRequest() throws Exception {
    checkResolve(githubPrOrigin(
        "url = 'https://github.com/google/example'",
        "required_labels = ['foo: yes', 'bar: yes']"),
        "https://github.com/google/example/pull/123", 123,
        ImmutableList.of("foo: yes", "bar: yes"));
  }

  @Test
  public void testGitResolvePullRequestNumber() throws Exception {
    checkResolve(githubPrOrigin(
        "url = 'https://github.com/google/example'",
        "required_labels = ['foo: yes', 'bar: yes']"),
        "123", 123, ImmutableList.of("foo: yes", "bar: yes"));
  }

  @Test
  public void testGitResolvePullRequestRawRef() throws Exception {
    checkResolve(githubPrOrigin(
        "url = 'https://github.com/google/example'",
        "required_labels = ['foo: yes', 'bar: yes']"),
        "refs/pull/123/head", 123, ImmutableList.of("foo: yes", "bar: yes"));
  }

  @Test
  public void testGitResolveSha1() throws Exception {
    GithubPROrigin origin = githubPrOrigin(
        "url = 'https://github.com/google/example'");
    checkResolve(origin,
        "refs/pull/123/head", 123, ImmutableList.of());

    // Test that we can resolve SHA-1 as long as they were fetched by the PR + base branch fetch.
    String sha1 = localHubRepo("google/example").parseRef("HEAD");
    GitRevision rev = origin
        .resolve(sha1 + " not important review data");

    assertThat(rev.getSha1()).isEqualTo(sha1);
  }

  @Test
  public void testGitResolveNoLabelsRequired() throws Exception {
    checkResolve(githubPrOrigin(
        "url = 'https://github.com/google/example'",
        "required_labels = []"),
        "125", 125, ImmutableList.of("bar: yes"));

    checkResolve(githubPrOrigin(
        "url = 'https://github.com/google/example'",
        "required_labels = []"),
        "126", 126, ImmutableList.of());
  }

  @Test
  public void testGitResolveRequiredLabelsNotFound() throws Exception {
    thrown.expect(ValidationException.class);
    thrown.expectMessage("Cannot migrate http://github.com/google/example/125 because it is missing"
        + " the following labels: [foo: yes]");
    checkResolve(githubPrOrigin(
        "url = 'https://github.com/google/example'",
        "required_labels = ['foo: yes', 'bar: yes']"),
        "125", 125, ImmutableList.of("bar: yes"));
  }

  @Test
  public void testGitResolveInvalidReference() throws Exception {
    thrown.expect(ValidationException.class);
    thrown.expectMessage("'master' is not a valid reference for a GitHub Pull Request");
    checkResolve(githubPrOrigin(
        "url = 'https://github.com/google/example'"),
        "master", 125, ImmutableList.of());
  }

  @Test
  public void testChanges() throws Exception {
    GitRepository remote = localHubRepo("google/example");
    addFiles(remote, "base", ImmutableMap.<String, String>builder()
        .put("test.txt", "a").build());
    String base = remote.parseRef("HEAD");
    addFiles(remote, "one", ImmutableMap.<String, String>builder()
        .put("test.txt", "b").build());
    addFiles(remote, "two", ImmutableMap.<String, String>builder()
        .put("test.txt", "c").build());
    String prHeadSha1 = remote.parseRef("HEAD");
    remote.simpleCommand("update-ref", GithubUtil.asHeadRef(123), prHeadSha1);

    withTmpWorktree(remote).simpleCommand("reset", "--hard", "HEAD~2"); // master = base commit.

    addFiles(remote, "master change", ImmutableMap.<String, String>builder()
        .put("other.txt", "").build());
    remote.simpleCommand("update-ref", GithubUtil.asMergeRef(123), remote.parseRef("HEAD"));

    githubMockHttpTransport = new MockPullRequest(123, ImmutableList.of());

    GithubPROrigin origin = githubPrOrigin(
        "url = 'https://github.com/google/example'");

    Reader<GitRevision> reader = origin.newReader(Glob.ALL_FILES, authoring);

    GitRevision prHead = origin.resolve("123");
    assertThat(prHead.getSha1()).isEqualTo(prHeadSha1);
    ImmutableList<Change<GitRevision>> changes = reader.changes(origin.resolve(base), prHead);

    assertThat(Lists.transform(changes, Change::getMessage))
        .isEqualTo(Lists.newArrayList("one\n", "two\n"));
    // Non-found baseline. We return all the changes between baseline and PR head.
    changes = reader.changes(origin.resolve(remote.parseRef("HEAD")), prHead);

    // Even if the PR is outdated it should return only the changes in the PR by finding the
    // common ancestor.
    assertThat(Lists.transform(changes, Change::getMessage))
        .isEqualTo(Lists.newArrayList("one\n", "two\n"));
  }

  @Test
  public void testCheckout() throws Exception {
    GitRepository remote = localHubRepo("google/example");
    addFiles(remote, "base", ImmutableMap.<String, String>builder()
        .put("test.txt", "a").build());
    addFiles(remote, "one", ImmutableMap.<String, String>builder()
        .put("test.txt", "b").build());
    addFiles(remote, "two", ImmutableMap.<String, String>builder()
        .put("test.txt", "c").build());
    String prHeadSha1 = remote.parseRef("HEAD");
    remote.simpleCommand("update-ref", GithubUtil.asHeadRef(123), prHeadSha1);

    withTmpWorktree(remote).simpleCommand("reset", "--hard", "HEAD~2"); // master = base commit.

    addFiles(remote, "master change", ImmutableMap.<String, String>builder()
        .put("other.txt", "").build());
    remote.simpleCommand("update-ref", GithubUtil.asMergeRef(123), remote.parseRef("HEAD"));

    githubMockHttpTransport = new MockPullRequest(123, ImmutableList.of());

    GithubPROrigin origin = githubPrOrigin(
        "url = 'https://github.com/google/example'");

    origin.newReader(Glob.ALL_FILES, authoring).checkout(origin.resolve("123"), workdir);

    FileSubjects.assertThatPath(workdir)
        .containsFile("test.txt", "c")
        .containsNoMoreFiles();

    // Now try with merge ref
    origin = githubPrOrigin(
        "url = 'https://github.com/google/example'",
        "use_merge = True");

    origin.newReader(Glob.ALL_FILES, authoring).checkout(origin.resolve("123"), workdir);

    FileSubjects.assertThatPath(workdir)
        .containsFile("other.txt", "")
        .containsNoMoreFiles();
  }

  @Test
  public void testCheckout_noMergeRef() throws Exception {
    GitRepository remote = localHubRepo("google/example");
    addFiles(remote, "base", ImmutableMap.<String, String>builder()
        .put("test.txt", "a").build());
    String prHeadSha1 = remote.parseRef("HEAD");
    remote.simpleCommand("update-ref", GithubUtil.asHeadRef(123), prHeadSha1);

    githubMockHttpTransport = new MockPullRequest(123, ImmutableList.of());

    // Now try with merge ref
    GithubPROrigin origin = githubPrOrigin(
        "url = 'https://github.com/google/example'",
        "use_merge = True");

    thrown.expect(CannotResolveRevisionException.class);
    thrown.expectMessage("Cannot find a merge reference for Pull Request 123");
    origin.newReader(Glob.ALL_FILES, authoring).checkout(origin.resolve("123"), workdir);
  }

  private void checkResolve(GithubPROrigin origin, String reference, int prNumber,
      final ImmutableList<String> presentLabels)
      throws RepoException, IOException, ValidationException {
    GitRepository remote = localHubRepo("google/example");
    addFiles(remote, "first change", ImmutableMap.<String, String>builder()
        .put(prNumber + ".txt", "").build());
    String sha1 = remote.parseRef("HEAD");
    remote.simpleCommand("update-ref", GithubUtil.asHeadRef(prNumber), sha1);

    githubMockHttpTransport = new MockPullRequest(prNumber, presentLabels);

    GitRevision rev = origin.resolve(reference);
    assertThat(rev.asString()).hasLength(40);
    assertThat(rev.contextReference()).isEqualTo(GithubUtil.asHeadRef(prNumber));
    assertThat(rev.associatedLabels()).containsEntry(GithubPROrigin.GITHUB_PR_NUMBER_LABEL,
        Integer.toString(prNumber));
    assertThat(rev.associatedLabels()).containsEntry(GitModule.DEFAULT_INTEGRATE_LABEL,
        "https://github.com/google/example/pull/" + prNumber
            + " from googletestuser:example-branch " + sha1);
  }

  private void addFiles(GitRepository remote, String msg, Map<String, String> files)
      throws IOException, RepoException {
    GitRepository tmpRepo = withTmpWorktree(remote);

    for (Entry<String, String> entry : files.entrySet()) {
      Path file = tmpRepo.getWorkTree().resolve(entry.getKey());
      Files.createDirectories(file.getParent());
      Files.write(file, entry.getValue().getBytes(UTF_8));
    }

    tmpRepo.add().all().run();
    tmpRepo.simpleCommand("commit", "-m", msg);
  }

  private GitRepository withTmpWorktree(GitRepository remote) throws IOException {
    return remote.withWorkTree(Files.createTempDirectory("temp"));
  }

  private GithubPROrigin githubPrOrigin(String... lines) throws ValidationException {
    return skylark.eval("r", "r = git.github_pr_origin("
        + "    " + Joiner.on(",\n    ").join(lines) + ",\n)");
  }

  private static class MockPullRequest extends GithubMockHttpTransport {

    private final int prNumber;
    private final ImmutableList<String> presentLabels;

    MockPullRequest(int prNumber, ImmutableList<String> presentLabels) {
      this.prNumber = prNumber;
      this.presentLabels = presentLabels;
    }

    @Override
    protected byte[] getContent(String method, String url, MockLowLevelHttpRequest request)
        throws IOException {
      if (url.equals("https://api.github.com/repos/google/example/issues/" + prNumber)) {
        return mockIssue(Integer.toString(prNumber), presentLabels).getBytes();
      } else if (url.startsWith(
          "https://api.github.com/repos/google/example/pulls/" + prNumber)) {
        return ("{\n"
            + "  \"id\": 1,\n"
            + "  \"number\": " + prNumber + ",\n"
            + "  \"state\": \"open\",\n"
            + "  \"title\": \"test summary\",\n"
            + "  \"body\": \"test summary\",\n"
            + "  \"head\": {\n"
            + "    \"label\": \"googletestuser:example-branch\",\n"
            + "    \"ref\": \"example-branch\"\n"
            + "   },\n"
            + "  \"base\": {\n"
            + "    \"label\": \"google:master\",\n"
            + "    \"ref\": \"master\"\n"
            + "   }\n"
            + "}").getBytes(UTF_8);
      }
      fail(method + " " + url);
      throw new IllegalStateException();
    }

    private String mockIssue(String number, ImmutableList<String> labels) {
      String result = "{\n"
          + "  \"id\": 1,\n"
          + "  \"number\": " + number + ",\n"
          + "  \"state\": \"open\",\n"
          + "  \"title\": \"test summary\",\n"
          + "  \"body\": \"test summary\"\n,"
          + "  \"labels\": [\n";
      for (String label : labels) {
        result += "    {\n"
            + "      \"id\": 111111,\n"
            + "      \"url\": \"https://api.github.com/repos/google/example/labels/foo:%20yes\",\n"
            + "      \"name\": \"" + label + "\",\n"
            + "      \"color\": \"009800\",\n"
            + "      \"default\": false\n"
            + "    },\n";
      }
      return result + "  ]\n"
          + "}";
    }
  }
}
