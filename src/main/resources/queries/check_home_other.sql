SELECT invite.player_uuid
FROM invite
  JOIN home ON home_uuid=home.player_uuid
  JOIN player ON home.player_uuid=player.uuid
WHERE player.name=?;