#!/usr/bin/env node

const path = require("path");
const fse = require("fs-extra");
const childProcess = require("child_process");
const glob = require("glob");
const _ = require("lodash");
const yargs = require("yargs");

const lernaJson = require("../lerna.json");

const argv = yargs
    .options({
        command: {
            description:
                "The command to run inside lerna run, after having bootstrapped",
            type: "string",
            required: true
        }
    })
    .help().argv;

const excludePackages = [
    "@magda/web-client",
    "@magda/preview-map",
    "@magda/web-admin",
    "@magda/web-server",
    "@magda/int-test",
    "@magda/search-api",
    "@magda/indexer",
    "@magda/registry-api",
    "@magda/scala-common"
];

const jsPackages = _(lernaJson.packages)
    .map(package => package + "/package.json")
    .flatMap(package => glob.sync(package))
    .filter(function(packagePath) {
        return !fse.existsSync(path.resolve(packagePath, "build.sbt"));
    })
    .map(packagePath => {
        const packageJson = require(path.resolve(packagePath));
        return packageJson.name;
    })
    .filter(packageName => excludePackages.indexOf(packageName) === -1)
    .value();

const commands = ["bootstrap", "run " + argv.command];

commands.forEach(command => {
    const result = childProcess.spawnSync(
        "lerna",
        [
            ...jsPackages.map(package => "--scope " + package),
            "--concurrency",
            "4",
            command
        ],
        {
            stdio: ["pipe", "inherit", "inherit"],
            shell: true
        }
    );

    if (result.status > 0) {
        process.exit(result.status);
    }
});

process.exit(0);
