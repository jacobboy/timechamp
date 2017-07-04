# timecop

An application for logging [TimeCamp](www.timecamp.com) time tracking
from [Google Calendar](calendar.google.com) and automatically filling
unallocated time.

## Usage

```
java -jar timecop fill-days [-s START_DATE] [-e END_DATE] [--hours-worked HOURS]
                            [-c CLIENT_SECRETS_FILE] [-d DATA_STORE_DIR]
                            [-t TC_API_TOKEN] [-i CALENDAR_ID] [-w]
                            [TASK_TIME_PAIR...]

java -jar timecop list-tasks [-t TC_API_TOKEN]
```

## fill-days
### Description
TimeCop will blindly shove Google calendar events into TimeCamp, and optionally
create events to fill unallocated time. *Currently it keeps only durations from
calendar events, throwing away specific start and end times by moving events to
the beginning of the workday.*

TimeCop allocates free time into tasks using the `TASK_TIME_PAIR` arguments,
where each pair maps a task ID to either an absolute length of time or a
percentage of free time spent on that task. TimeCop first creates tasks from the
absolute durations provided, then, if any time remains available in the day,
subdivides the free time according to any percentages supplied.

You are required to have Google service account credentials, a TimeCamp API
token, and knowledge of the somewhat hidden TimeCamp task IDs. These
requirements are described in more detail their own sections below.

### Arguments:
#### Positional arguments:
`TASK_TIME_PAIR...`  
_Optional._  
_Ex:_ `1234 1h 2345 1h30m 3456 80% 4567 20%`

A `TASK_TIME_PAIR` is a space-separated pair of a (numeric) TimeCamp task id and
either an absolute length time or a percentage of free time. Timecop will first
create events from any tasks with absolute durations provided, then, if total
event duration is less than hours worked, will portion the remaining time
according to any percentages supplied.

Absolute times are specifed as hours and/or minutes in the form `XhYm`,
e.g. `1h30m`, `1.5h`, `90m`. Percentages are specified as `X%`, e.g. `25%`. Thus
the list of `TASK_TIME_PAIR...` might look like
`1234 1h 2345 30m 3456 80% 4567 20%`.

Hours and minutes may be floats, but will be rounded to the nearest minute.
Percents may be floats and need not sum to 1; it is valid to specify tasks take
up only a fraction of the unallocated time.  Percentages greater than 1 are also
respected for some reason.

#### Named arguments:

`-s, --start-date START_DATE`  
_Default: Current date_  
Start date (inclusive) in `yyyy-MM-dd` format

`-e, --end-date END_DATE`  
_Default: Current date_  
End date (inclusive) in `yyyy-MM-dd` format

`--hours-worked HOURS`  
_Default:_ `8`  
Hours worked per day. Must be less than 15, as events are moved to begin at 9am.

`-c, --client-secrets-file CLIENT_SECRETS_FILE`  
_Default:_ `$TIMECOP_SECRETS_FILE`  
Path to Google client secrets JSON file. See below for more information.

`-d, --data-store-dir DATA_STORE_DIR`  
_Default:_ `$TIMECOP_CREDENTIALS_DIR`  
Path to the folder where the Google client will save its auth information
between uses, allowing you to not need to reauthorize your Google credentials
each use. This can be `/tmp` if desired, though you'll have to re-authorize as
it is cleaned up.

`-t, --tc-api-token TC_API_TOKEN`  
_Default:_ `$TC_API_TOKEN`  
TimeCamp API token. See below for more information.

`-i, --calendar-id CALENDAR_ID`  
_Default:_ `"primary"`  
ID of the Google calendar to read events from. Unless you've created multiple
calendars on the account and know which you want, the default is what you want.

`-w, --include-weekends`  
Create events for weekends.

`-h, --help`  

## list-tasks
Prints TimeCamp tasks and task IDs available to the provided TimeCamp account.

### Arguments
#### Named arguments:
`-t, --tc-api-token TC_API_TOKEN`  
_Default:_ `$TC_API_TOKEN`  
TimeCamp API token. See below for more information.

## Getting Started

The easiest way to run this is to download the
[latest jar file from Github](https://github.com/jacobboy/timecop/releases)
and run

``` shell
java -jar path/to/jar <subcommand> [<args>]
```
e.g.
``` shell
java -jar timecop.jar fill-days 9238 1h30m 8302 75m 8380 50% 8856 10%
```
To run or build from source, install [leiningen](https://leiningen.org/), cd to
the base of the repo, and execute
`lein run -- <args>` to run or `lein uberjar` to package.

## Requirements
### Task IDs
A list of all task IDs available to your account is available through the
`list-tasks` subcommand. Provide your API token via the `-t` option or
TC_API_TOKEN environment variable.  This subcommand prints a human readable
mapping of tasks and IDs.

### Credentials

#### Google Credentials
To retrieve data from your Google calendar, you will need an OAuth client ID
from a project with access to the Calendar API. To obtain this, as of mid-2017
you can:

##### Create a new project
If you haven't created a project before, go to
https://console.developers.google.com/projectselector/apis/credentials
where it will prompt you to create a new project. The name is not required to
match this application.

##### Create OAuth client ID
1. In the Credentials section, click "Create credentials" and select "OAuth
   client ID".
2. For a new project, it'll have you configure the consent screen.  You'll have
   to provide the name that'll be shown on the OAuth consent screen, the rest of
   the form is optional. It is not important what you choose. Click "Save".
3. Select Other as the application type. It'll give you a pop-up with your
   client ID and secret, which you need not save. Click through and you'll be
   returned to the Credentials page.
4. Download the client secrets file by hitting the download button for the
   credentials you just created. Move this to somewhere it won't get lost.  The
   path to the downloaded file will be provided to TimeCop either via command
   line argument or from the `TIMECOP_SECRETS_FILE` environment variable.

##### Enable Calendar API for this project
Click the Library tab or go to
https://console.developers.google.com/apis/library, search for and select Google
Calendar API, hit Enable at the top.


#### TimeCamp API token
Your TimeCamp API token is available at the bottom of the User Settings
page. This can be provided to TimeCop either via command line argument or read
from the `TC_API_TOKEN` environment variable.

## License

Copyright © 2017 Jacob Boy

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0
(http://opensource.org/licenses/eclipse-1.0.php) which can be found in
the file LICENSE at the root of this distribution.
