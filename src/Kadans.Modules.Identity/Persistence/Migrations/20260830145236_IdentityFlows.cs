using System;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Modules.Identity.Persistence.Migrations
{
    /// <inheritdoc />
    public partial class IdentityFlows : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            // Plaintext tokens cannot be converted into hashed token families; existing sessions
            // are dropped and every client signs in again once after this deploys.
            migrationBuilder.Sql("DELETE FROM identity.refresh_tokens;");

            migrationBuilder.DropIndex(
                name: "IX_refresh_tokens_token",
                schema: "identity",
                table: "refresh_tokens");

            migrationBuilder.DropIndex(
                name: "IX_refresh_tokens_user_id",
                schema: "identity",
                table: "refresh_tokens");

            migrationBuilder.DropColumn(
                name: "token",
                schema: "identity",
                table: "refresh_tokens");

            migrationBuilder.AddColumn<Guid>(
                name: "family_id",
                schema: "identity",
                table: "refresh_tokens",
                type: "uuid",
                nullable: false,
                defaultValue: new Guid("00000000-0000-0000-0000-000000000000"));

            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "revoked_at_utc",
                schema: "identity",
                table: "refresh_tokens",
                type: "timestamp with time zone",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "revoked_reason",
                schema: "identity",
                table: "refresh_tokens",
                type: "character varying(100)",
                maxLength: 100,
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "token_hash",
                schema: "identity",
                table: "refresh_tokens",
                type: "character varying(64)",
                maxLength: 64,
                nullable: false,
                defaultValue: "");

            migrationBuilder.CreateTable(
                name: "devices",
                schema: "identity",
                columns: table => new
                {
                    id = table.Column<Guid>(type: "uuid", nullable: false),
                    installation_id = table.Column<Guid>(type: "uuid", nullable: false),
                    user_id = table.Column<string>(type: "character varying(450)", maxLength: 450, nullable: false),
                    platform = table.Column<string>(type: "text", nullable: false),
                    name = table.Column<string>(type: "character varying(200)", maxLength: 200, nullable: false),
                    push_token = table.Column<string>(type: "character varying(4096)", maxLength: 4096, nullable: true),
                    app_version = table.Column<string>(type: "character varying(50)", maxLength: 50, nullable: true),
                    registered_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false),
                    last_seen_at = table.Column<DateTimeOffset>(type: "timestamp with time zone", nullable: false)
                },
                constraints: table =>
                {
                    table.PrimaryKey("pk_devices", x => x.id);
                    table.ForeignKey(
                        name: "FK_devices_asp_net_users_user_id",
                        column: x => x.user_id,
                        principalSchema: "identity",
                        principalTable: "asp_net_users",
                        principalColumn: "id",
                        onDelete: ReferentialAction.Cascade);
                });

            migrationBuilder.CreateIndex(
                name: "IX_refresh_tokens_family_id",
                schema: "identity",
                table: "refresh_tokens",
                column: "family_id");

            migrationBuilder.CreateIndex(
                name: "IX_refresh_tokens_token_hash",
                schema: "identity",
                table: "refresh_tokens",
                column: "token_hash",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_refresh_tokens_user_id_is_active",
                schema: "identity",
                table: "refresh_tokens",
                columns: new[] { "user_id", "is_active" });

            migrationBuilder.CreateIndex(
                name: "IX_devices_user_id_installation_id",
                schema: "identity",
                table: "devices",
                columns: new[] { "user_id", "installation_id" },
                unique: true);
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropTable(
                name: "devices",
                schema: "identity");

            migrationBuilder.DropIndex(
                name: "IX_refresh_tokens_family_id",
                schema: "identity",
                table: "refresh_tokens");

            migrationBuilder.DropIndex(
                name: "IX_refresh_tokens_token_hash",
                schema: "identity",
                table: "refresh_tokens");

            migrationBuilder.DropIndex(
                name: "IX_refresh_tokens_user_id_is_active",
                schema: "identity",
                table: "refresh_tokens");

            migrationBuilder.DropColumn(
                name: "family_id",
                schema: "identity",
                table: "refresh_tokens");

            migrationBuilder.DropColumn(
                name: "revoked_at_utc",
                schema: "identity",
                table: "refresh_tokens");

            migrationBuilder.DropColumn(
                name: "revoked_reason",
                schema: "identity",
                table: "refresh_tokens");

            migrationBuilder.DropColumn(
                name: "token_hash",
                schema: "identity",
                table: "refresh_tokens");

            migrationBuilder.AddColumn<string>(
                name: "token",
                schema: "identity",
                table: "refresh_tokens",
                type: "character varying(512)",
                maxLength: 512,
                nullable: false,
                defaultValue: "");

            migrationBuilder.CreateIndex(
                name: "IX_refresh_tokens_token",
                schema: "identity",
                table: "refresh_tokens",
                column: "token",
                unique: true);

            migrationBuilder.CreateIndex(
                name: "IX_refresh_tokens_user_id",
                schema: "identity",
                table: "refresh_tokens",
                column: "user_id");
        }
    }
}
