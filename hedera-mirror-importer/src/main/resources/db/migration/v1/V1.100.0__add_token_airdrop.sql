create type airdrop_state as enum ('CANCELLED', 'CLAIMED', 'PENDING');

create table if not exists token_airdrop
(
    amount              bigint,
    receiver_account_id bigint         not null,
    sender_account_id   bigint         not null,
    serial_number       bigint         not null,
    state               airdrop_state  not null default 'PENDING',
    timestamp_range     int8range      not null,
    token_id            bigint         not null
);

create unique index if not exists token_airdrop__sender_id on token_airdrop (sender_account_id, receiver_account_id, token_id, serial_number);
create index if not exists token_airdrop__receiver_id on token_airdrop (receiver_account_id, sender_account_id, token_id, serial_number);

create table if not exists token_airdrop_history
(
    like token_airdrop including defaults
);

create index if not exists token_airdrop_history__timestamp_range
    on token_airdrop_history using gist (timestamp_range);
