# timecop

A Clojure application for logging [TimeCamp](www.timecamp.com) time tracking from
[Google Calendar](calendar.google.com).

## Usage

You will require a Google Service Account stuff
TimeCamp API token

The easiest way to find a specific task ID is to query TimeCamp's `entries` endpoint at
`https://www.timecamp.com/third_party/api/entries/format/json/api_token/<api_token>/from/<yyyy-MM-dd>/to/<yyyy-MM-dd>/user_ids/<user_id>`
for a day that you have logged time under that task, formatting in dates, API token, and your user
id.  To find your user ID, query the `users` endpoint at
`https://www.timecamp.com/third_party/api/user/format/json/api_token/3a54a61a816a695543f7a0e07c`
and locate the entry with your email.  A helper for this is coming.
All TimeCamp task ids can be found by making a get request to TimeCamp's tasks endpoint at
`https://www.timecamp.com/third_party/api/tasks/format/json/api_token/<api_token>`, but this is
not particularly helpful.



## License

Copyright © 2017 Jacob Boy

The use and distribution terms for this software are covered by the Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which can be found in the file LICENSE at the root of this distribution.

Medicare
Non-specific meetings - 9238867
Optimizations and improvements - 9238868
Code review - 9468380
Unproductive time - 9238856
