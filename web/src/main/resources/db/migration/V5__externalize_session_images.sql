-- Externalise inline data: images left in messages jsonb by pre-V4 code:
-- pixels move to agent.session_images, jsonb carries ref:<sessionId>/<imageId>.
-- Idempotent: ref'd images are skipped and inserts are on-conflict-do-nothing,
-- so re-running is always safe. The id is SHA-256 hex of the UTF-8 content,
-- matching the Java-side externalisation, so the same image dedupes across both.
do $$
declare
    s record;
    m record;
    img record;
    new_messages jsonb;
    new_images jsonb;
    img_id text;
begin
    for s in select id, messages from agent.sessions loop
        new_messages := '[]'::jsonb;
        for m in select value as msg from jsonb_array_elements(s.messages) loop
            if jsonb_typeof(m.msg->'images') = 'array' then
                new_images := '[]'::jsonb;
                for img in select value as image from jsonb_array_elements_text(m.msg->'images') loop
                    if img.image like 'data:%' then
                        img_id := encode(sha256(convert_to(img.image, 'UTF8')), 'hex');
                        insert into agent.session_images (session_id, image_id, content)
                        values (s.id, img_id, img.image)
                        on conflict do nothing;
                        new_images := new_images || jsonb_build_array('ref:' || s.id::text || '/' || img_id);
                    else
                        new_images := new_images || jsonb_build_array(img.image);
                    end if;
                end loop;
                new_messages := new_messages || jsonb_build_array(jsonb_set(m.msg, '{images}', new_images));
            else
                new_messages := new_messages || jsonb_build_array(m.msg);
            end if;
        end loop;
        if new_messages is distinct from s.messages then
            update agent.sessions set messages = new_messages where id = s.id;
        end if;
    end loop;
end $$;
