create table agent.session_images (
    session_id uuid not null,
    image_id text not null,
    content text not null,
    primary key (session_id, image_id)
);
