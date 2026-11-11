# CI-only privacy-sanitized export

This parentless test branch exists only to run hosted checks for web-app source commit `1d1fe83fcf7e984c02de690e56abbc45f4bb5c99`. It is not a release, preview, or deployment candidate.

The application source, tests, lockfile, and public resources are byte-identical to that source commit. Non-runtime internal plans and documentation, plus SQL files with personal metadata in comments, are omitted. `README.md` is retained unchanged because `tests/links.test.ts` reads it. The original project workflows are not carried; this branch has only its isolated CI workflow.

`vercel.json` intentionally differs on this branch: `git.deploymentEnabled: false` disables automatic Git deployments, and `ignoreCommand: "exit 0"` skips a Vercel build if one is accidentally invoked. These guards are test-branch-only and must not be copied into the production app. See [Vercel Git Configuration](https://vercel.com/docs/project-configuration/git-configuration) and [vercel.json `ignoreCommand`](https://vercel.com/docs/project-configuration/vercel-json#ignorecommand).

The workflow has read-only repository permissions and uses synthetic CI values on `.invalid` hosts; it does not use production secrets, Vercel credentials, or a production database. Test and build diagnostics are withheld from logs to avoid publishing fixture or diary text.
