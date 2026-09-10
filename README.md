# photo-uploader-app

The application half of the **Photo Uploader** lab: a minimal Spring Boot
photo gallery with no user authentication, as required by the lab spec.
It uploads photos to S3, stores descriptions in RDS PostgreSQL, and
displays each photo via its CloudFront URL. Infrastructure (the
CloudFormation that provisions the VPC, S3 bucket, CloudFront
distribution, RDS instance, ECS service, and CI/CD pipeline this app
deploys into) lives in the separate
[`photo-uploader-infra`](https://github.com/1MuhireDavid/photo-uploader-infra)
repo.

## Layout

```
src/main/java/com/labs/photouploader/
  PhotoUploaderApplication.java   # Spring Boot entry point
  Photo.java                       # JPA entity (s3Key, description, uploadedAt, ...)
  PhotoRepository.java              # Spring Data JPA
  GalleryController.java             # GET / (gallery + upload form), POST /upload
  S3Config.java                       # S3Client bean (task-role credentials, no access keys)
src/main/resources/
  application.yaml                  # reads DB_*/PHOTOS_BUCKET_NAME/CLOUDFRONT_DOMAIN/... from env
  templates/gallery.html             # the whole UI
Dockerfile                           # multi-stage build, non-root runtime user
ecs/
  taskdef.json                       # CodeDeploy task definition template
  appspec.yaml                       # CodeDeploy appspec template
```
(the GitHub Actions workflow that builds/pushes this app lives at
`.github/workflows/build-and-push.yml` -- this repo holds only this app,
so unlike a shared monorepo lab there's no path filter needed)

## How the app talks to AWS -- no credentials in the app at all

- **S3 (photo uploads):** `S3Config` builds an `S3Client` using
  `DefaultCredentialsProvider`, which on ECS Fargate resolves to the
  task's IAM role automatically via the container credentials endpoint.
  No access key/secret ever exists in this app or its image.
- **RDS PostgreSQL:** the JDBC URL/username/password come entirely from
  environment variables (`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USERNAME`/
  `DB_PASSWORD`) injected by the ECS task definition -- the last two are
  pulled from the RDS-managed Secrets Manager secret via the task
  definition's `secrets` field, not a plaintext value anywhere.
- **CloudFront:** the app never calls the CloudFront API -- it just knows
  its domain name (`CLOUDFRONT_DOMAIN` env var) and builds
  `https://<domain>/<s3Key>` URLs for `<img>` tags. CloudFront fetches the
  actual bytes from S3 itself, using Origin Access Control (see the infra
  repo).

## Run locally

Requires a local PostgreSQL and (optionally) real AWS credentials with S3
access for the upload path to fully work; without them the gallery page
still loads (just against an empty/failing DB, matching how the ALB
health check would see it):



```bash
cd photo-uploader-app
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--DB_HOST=localhost --DB_NAME=photogallery --DB_USERNAME=photoapp --DB_PASSWORD=photoapp --PHOTOS_BUCKET_NAME=my-test-bucket --CLOUDFRONT_DOMAIN=example.cloudfront.net"
# or
docker build -t photo-uploader .
docker run -p 8080:8080 \
  -e DB_HOST=host.docker.internal -e DB_NAME=photogallery \
  -e DB_USERNAME=photoapp -e DB_PASSWORD=photoapp \
  -e PHOTOS_BUCKET_NAME=my-test-bucket -e CLOUDFRONT_DOMAIN=example.cloudfront.net \
  photo-uploader
curl localhost:8080
```

## Image tagging strategy: keep it simple

Every successful build on `main` pushes a single tag, `latest`, to the
image -- the one tag EventBridge and the CodePipeline ECR source action
watch, always "the newest thing on `main`".

This only works because the ECR repository itself is created with
`ImageTagMutability: MUTABLE` (`photo-uploader-infra/cfn/modules/
03-backing-services.yaml`); otherwise re-pushing `:latest` on every build
would be rejected.

## Why a bootstrap placeholder image

The ECS service, ALB target groups, and CodeDeploy all get created by
CloudFormation the very first time the infra stack runs -- before this
app's GitHub Action has ever pushed a real image. To avoid a chicken-
and-egg failure (`CREATE_COMPLETE` blocked on an image that doesn't
exist), the task definition's `InitialImageTag` parameter defaults to the
sentinel value `bootstrap`, which the infra template swaps for a public
`nginx` image (remapped to listen on the same container port) -- see
`../photo-uploader-infra/cfn/modules/06-alb-ecs.yaml`. The ALB health
check path is `/` for exactly this reason: it's the one path both nginx
and this app answer with `200`. Once you push a real image and CodeDeploy
runs its first blue/green release, CodeDeploy -- not this parameter --
owns the running task definition from then on.

## One-time setup for `ecs/taskdef.json`

`taskdef.json` is a template, but CodePipeline's `CodeDeployToECS` action
only substitutes the `<IMAGE1_NAME>` placeholder automatically. Everything
else is a stable value once the infra stack has deployed, so fill it in
once by editing the file directly -- no CLI needed. Open `ecs/taskdef.json`
in GitHub's web editor (pencil icon) or any local text editor and replace:

| Placeholder | Where to find it |
|---|---|
| `<AWS_ACCOUNT_ID>` | AWS Console, top-right account menu (your 12-digit Account ID) |
| `<AWS_REGION>` | AWS Console's top-right region selector (wherever you deployed the infra stack) |
| `<YOUR_FULL_NAME>` | your name, exactly as you want it displayed |
| `<PHOTOS_BUCKET_NAME>` | CloudFormation console -> root stack (`photo-uploader`) -> Outputs -> `PhotosBucketName` |
| `<CLOUDFRONT_DOMAIN>` | same Outputs tab -> `CloudFrontDomainName` |
| `<DB_HOST>` | same Outputs tab -> `DbEndpointAddress` |
| `<DB_SECRET_ARN>` | CloudFormation console -> `photo-uploader-DatabaseStack-*` nested stack -> Outputs -> `DbSecretArn` (or RDS console -> your DB instance -> "Master credentials ARN") |

Commit directly to `main` (GitHub web UI's **Commit changes** button, or a
normal `git commit` + `git push`).

`ecs/appspec.yaml`'s `<TASK_DEFINITION>` placeholder is different: it's a
**literal string** CodeDeploy itself substitutes at deploy time with the
ARN of the task definition revision it just registered -- leave it as-is.

## Required GitHub repo secrets

Added via **Settings -> Secrets and variables -> Actions** on this repo,
using values read from the bootstrap stack's Outputs tab (see the infra
repo's README):

| Secret / variable | Example |
|---|---|
| `AWS_ECR_PUSH_ROLE_ARN` (secret) | `arn:aws:iam::123456789012:role/photo-uploader-gha-ecr-push-role` |
| `ECR_REPOSITORY` (secret) | `photo-uploader-app` |
| `AWS_REGION` (variable) | `us-east-1` |
| `PIPELINE_ARTIFACT_BUCKET` (variable) | the `ArtifactBucketName` output from the infra repo's root stack -- where `ecs/appspec.yaml` + `ecs/taskdef.json` get zipped and uploaded to for CodePipeline to pick up |

## What happens on push to `main`

1. `build-and-push.yml` assumes `AWS_ECR_PUSH_ROLE_ARN` via **OIDC** -- a
   role that (via the `job_workflow_ref` trust condition) only this exact
   workflow file, in this exact repo, can assume.
2. Builds the image, tags it `latest`, pushes it, then zips this repo's
   `ecs/appspec.yaml` + `ecs/taskdef.json` and uploads that zip to S3
   (`PIPELINE_ARTIFACT_BUCKET`) -- no GitHub connection for AWS to read
   this repo directly.
3. The `:latest` push fires an `ECR Image Action` event -> the
   `EcrPushRule` EventBridge rule in the infra stack -> starts
   `photo-uploader-pipeline`.
4. CodePipeline reads the new image URI (ECR source action) and the
   deploy-templates zip just uploaded to S3 (S3 source action,
   `PollForSourceChanges: false` so it never self-triggers on every
   upload), hands both to CodeDeploy.
5. CodeDeploy registers a new task definition revision, spins up "green"
   tasks, waits for them to pass the ALB health check, shifts the
   listener's traffic from "blue" to "green", then terminates the old
   "blue" tasks.
